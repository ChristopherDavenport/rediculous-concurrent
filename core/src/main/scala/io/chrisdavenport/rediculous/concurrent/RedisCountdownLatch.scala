package io.chrisdavenport.rediculous.concurrent

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent._
import io.chrisdavenport.rediculous._
import io.circe._
import io.circe.syntax._
import io.chrisdavenport.rediculous.concurrent.RedisCountdownLatch.Awaiting
import io.chrisdavenport.rediculous.concurrent.RedisCountdownLatch.Done
import scala.concurrent.duration._
import cats.syntax.TryOps
import cats.instances.finiteDuration

abstract class CountDownLatch[F[_]] { self =>

  /**
   * Release a latch, decrementing the remaining count and
   * releasing any fibers that are blocked if the count
   * reaches 0
   */
  def release: F[Unit]

  /**
   * Semantically block until the count reaches 0
   */
  def await: F[Unit]

  def mapK[G[_]](f: F ~> G): CountDownLatch[G] =
    new CountDownLatch[G] {
      def release: G[Unit] = f(self.release)
      def await: G[Unit] = f(self.await)
    }

}

object RedisCountdownLatch {

  def createOrAccess[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    key: String,
    latches: Int,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration,
    pollingInterval: FiniteDuration,
    deferredLifetime: FiniteDuration,
    setOpts: RedisCommands.SetOpts
  ): F[CountDownLatch[F]] = {
    val ref = RedisRef.liftedSimple(
      stateAtLocation(redisConnection, key, acquireTimeout, lockTimeout, setOpts),
      (Awaiting(latches, key ++ ":gate"): State)
    )

    ref.update(identity)
      .as(new ConcurrentCountDownLatch[F](ref, pollingInterval, redisConnection, key, deferredLifetime))
  }

  def accessAtKey[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    key: String,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration,
    pollingInterval: FiniteDuration,
    deferredLifetime: FiniteDuration,
    setOpts: RedisCommands.SetOpts
  ): CountDownLatch[F] = {
    val ref = stateAtLocation(redisConnection, key, acquireTimeout, lockTimeout, setOpts)

    new PossiblyAbsentCountdownLatch[F](
      ref,
      pollingInterval,
      redisConnection,
      key,
      deferredLifetime
    )
  }

  private class ConcurrentCountDownLatch[F[_]: Concurrent: Timer](
    state: Ref[F, State],
    pollingInterval: FiniteDuration,
    redisConnection: RedisConnection[F],
    keyLocation: String,
    lifetime: FiniteDuration
  )
      extends CountDownLatch[F] {

    override def release: F[Unit] =
      Concurrent[F].uncancelable {
        state.modify {
          case Awaiting(n, signal) =>
            if (n > 1) (Awaiting(n - 1, signal), Applicative[F].unit) else (Done(), RedisDeferred.fromKey(redisConnection, signal, pollingInterval, lifetime).complete(keyLocation).void)
          case d @ Done() => (d, Applicative[F].unit)
        }.flatten
      }

    override def await: F[Unit] =
      state.get.flatMap {
        case Awaiting(_, signal) => RedisDeferred.fromKey(redisConnection, signal, pollingInterval, lifetime).get.void
        case Done() => Applicative[F].unit
      }

  }

  private class PossiblyAbsentCountdownLatch[F[_]: Concurrent: Timer](
    state: Ref[F, Option[State]], 
    pollingInterval: FiniteDuration,
    redisConnection: RedisConnection[F],
    keyLocation: String,
    lifetime: FiniteDuration
  ) extends CountDownLatch[F] {
    override def release: F[Unit] =
      Concurrent[F].uncancelable {
        state.modify {
          case Some(Awaiting(n, signal)) =>
            if (n > 1) (Awaiting(n - 1, signal).some, false.pure[F]) else (Done().some, RedisDeferred.fromKey(redisConnection, signal, pollingInterval, lifetime).complete(keyLocation).void.as(false))
          case Some(d @ Done()) => (d.some, false.pure[F])
          case None => (None, true.pure[F])
        }.flatten
      }.ifM(
        Timer[F].sleep(pollingInterval) >> release, 
        Applicative[F].unit
      )

    override def await: F[Unit] =
      state.get.flatMap {
        case Some(Awaiting(_, signal)) => RedisDeferred.fromKey(redisConnection, signal, pollingInterval, lifetime).get.void
        case Some(Done()) => Applicative[F].unit
        case None => Timer[F].sleep(pollingInterval) >> await
      }
  }

  def stateAtLocation[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    key: String,
    acquireTimeout: FiniteDuration,
    lockTimeout: FiniteDuration,
    setOpts: RedisCommands.SetOpts
  ): Ref[F, Option[State]] = 
    RedisRef.optionJsonRef(
      RedisRef.lockedOptionRef(redisConnection, key, acquireTimeout, lockTimeout, setOpts)
    )

  sealed trait State
  case class Awaiting(latches: Int, signalKey: String) // Turn into Deferred 
      extends State
  case class Done() extends State

  def liftDeferred[F[_]: Functor, A](
    tryAble: Deferred[F, A],
    default: A 
  ): Deferred[F, Unit] = new TranslatedDeferred[F, A](tryAble, default)

  def liftTryableDeferred[F[_]: Functor, A](
    tryAble: TryableDeferred[F, A],
    default: A 
  ): TryableDeferred[F, Unit] = new TranslatedTryDeferred[F, A](tryAble, default)

  class TranslatedTryDeferred[F[_]: Functor, A](
    val tryAble: TryableDeferred[F, A],
    val default: A
  ) extends TryableDeferred[F, Unit]{
    def complete(a: Unit): F[Unit] = 
      tryAble.complete(default)
    def get: F[Unit] = 
      tryAble.get.void
    def tryGet: F[Option[Unit]] = 
      tryAble.tryGet.map(_.void)
  }

  class TranslatedDeferred[F[_]: Functor, A](
    val tryAble: Deferred[F, A],
    val default: A
  ) extends Deferred[F, Unit]{
    def complete(a: Unit): F[Unit] = 
      tryAble.complete(default)
    def get: F[Unit] = 
      tryAble.get.void
  }


  implicit val encoder: Encoder[State] = Encoder.instance[State]{
    case Awaiting(latches, signalKey) => Json.obj(
        "state" -> "Awaiting".asJson,
        "latches" -> latches.asJson,
        "signal" -> signalKey.asJson
    )
    case Done() => Json.obj(
      "state" -> "Done".asJson
    )
  }

  implicit val decoder: Decoder[State] = new Decoder[State]{ 
    def apply(h: HCursor): Decoder.Result[State] =  {
      for {
        state <- h.downField("state").as[String]
        out <- state match {
          case "Awaiting" => (
            h.downField("latches").as[Int],
            h.downField("signal").as[String]
          ).mapN(Awaiting(_, _): State)
          case "Done" => Either.right(Done(): State)
          case otherwise => Either.left(DecodingFailure(s"Incorrect State - $otherwise", List()))
        }
      } yield out
    }
  }

}


