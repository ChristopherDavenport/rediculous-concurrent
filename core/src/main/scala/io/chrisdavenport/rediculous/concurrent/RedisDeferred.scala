package io.chrisdavenport.rediculous.concurrent

import io.chrisdavenport.rediculous._
import cats.effect._
import cats.data.NonEmptyList
import cats.effect.concurrent._
import cats.syntax.all._
import scala.concurrent.duration.FiniteDuration
import io.chrisdavenport.rediculous.RedisCommands.Condition
import io.chrisdavenport.rediculous.RedisProtocol.Status.Ok
import io.chrisdavenport.rediculous.RedisProtocol.Status.Pong
import io.chrisdavenport.rediculous.RedisProtocol.Status.Status
import cats.Applicative

object RedisDeferred {

  /**
    * Creates A Unique Deferred, returning the key and a deferred instance that can be used.
    */
  def create[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    pollingInterval: FiniteDuration, 
    lifetime: FiniteDuration
  ): F[(String, TryableDeferred[F, String])] = Sync[F].delay(java.util.UUID.randomUUID()).map{identifier => 
    val key = s"deferred:${identifier}"
    (key, fromKey(redisConnection, key, pollingInterval, lifetime))
  }

  def fromKey[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    keyLocation: String,
    pollingInterval: FiniteDuration,
    lifetime: FiniteDuration
  ): TryableDeferred[F, String] = new LocationDeferredRef[F](redisConnection, keyLocation, pollingInterval, lifetime)

  class LocationDeferredRef[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    keyLocation: String,
    pollingInterval: FiniteDuration,
    lifetime: FiniteDuration
  ) extends TryableDeferred[F, String] {

    def tryGet: F[Option[String]] = 
      RedisCommands.get(keyLocation).run(redisConnection)

    def get: F[String] = 
      RedisCommands.get(keyLocation).run(redisConnection).flatMap{
        case None => Timer[F].sleep(pollingInterval) >> get
        case Some(a) => a.pure[F]
      }
    
    def complete(a: String): F[Unit] = {
      // val ctxState = RedisResult.option[RedisProtocol.Status](RedisResult.status)
      RedisCtx[Redis[F, *]].keyed[Option[RedisProtocol.Status]](keyLocation, NonEmptyList.of("SET", keyLocation, a, "PX", lifetime.toMillis.toString(), "NX"))
      // RedisCommands.set(keyLocation, a, RedisCommands.SetOpts(None, Some(lifetime.toMillis), Some(Condition.Nx), false))
        .run(redisConnection)
        .flatMap{
          case None => Timer[F].sleep(pollingInterval) >> complete(a)
          case Some(Ok) => Applicative[F].unit
          case Some(Pong) => Concurrent[F].raiseError[Unit](
            new IllegalStateException("Attempting to complete a Deferred got Pong: should never arrive here")
          )
          case Some(Status(getStatus)) => Concurrent[F].raiseError[Unit](
            new IllegalStateException("Attempting to complete a Deferred that has already been completed")
          )
        }
    }
    

  }
}