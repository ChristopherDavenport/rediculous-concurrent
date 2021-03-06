package io.chrisdavenport.rediculous.concurrent

import cats._
import cats.syntax.all._
import io.chrisdavenport.circuit.CircuitBreaker.State
import io.chrisdavenport.circuit.CircuitBreaker
import io.circe._
import io.chrisdavenport.circuit.CircuitBreaker.Closed
import io.chrisdavenport.circuit.CircuitBreaker.HalfOpen
import io.chrisdavenport.circuit.CircuitBreaker.Open


import io.circe.syntax._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import cats.effect._
import cats.effect.concurrent.Ref
import io.chrisdavenport.rediculous.{RedisCommands,RedisConnection}

object RedisCircuit {

  def circuitAtLocation[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    key: String,
    acquireTimeout: FiniteDuration = 5.seconds,
    lockDuration: FiniteDuration = 10.seconds,
    setOpts: RedisCommands.SetOpts,
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double,
    maxResetTimeout: Duration
  ): CircuitBreaker[F] = {
    val ref = RedisRef.liftedDefaultStorage(
      RedisRef.optionJsonRef[F, State](RedisRef.lockedOptionRef(redisConnection, key, acquireTimeout, lockDuration, setOpts)),
      CircuitBreaker.Closed(0)
    )
    CircuitBreaker.unsafe(ref, maxFailures, resetTimeout, exponentialBackoffFactor, maxResetTimeout, Applicative[F].unit, Applicative[F].unit, Applicative[F].unit, Applicative[F].unit)
  }

  def keyCircuit[F[_]: Concurrent: Timer](
    redisConnection: RedisConnection[F],
    acquireTimeout: FiniteDuration = 5.seconds,
    lockDuration: FiniteDuration = 10.seconds,
    setOpts: RedisCommands.SetOpts,
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double,
    maxResetTimeout: Duration
  ): String => CircuitBreaker[F] = {
    val base: RedisMapRef[F] = RedisMapRef.impl[F](redisConnection, acquireTimeout, lockDuration, setOpts)(Concurrent[F], Timer[F])
    val closed: String = (CircuitBreaker.Closed(0): State).asJson.noSpaces

    {key: String => 
      val ref = RedisRef.liftedDefaultStorage(base.apply(key), closed)
      val stateRef = RedisRef.jsonRef[F, State](ref)

      CircuitBreaker.unsafe(stateRef, maxFailures, resetTimeout, exponentialBackoffFactor, maxResetTimeout, Applicative[F].unit, Applicative[F].unit, Applicative[F].unit, Applicative[F].unit)
    }
  }

  implicit private val eqState: Eq[State] = Eq.instance{
    case (HalfOpen, HalfOpen) => true
    case (Closed(i), Closed(i2)) => i === i2
    case (Open(started1, reset1), Open(started2, reset2)) => 
      started1 === started2 &&
        reset1 === reset2
    case (_, _) => false
  }

  implicit private final val finiteDurationDecoder: Decoder[FiniteDuration] =
    new Decoder[FiniteDuration] {
      def apply(c: HCursor): Decoder.Result[FiniteDuration] = for {
        length <- c.downField("length").as[Long].right
        unitString <- c.downField("unit").as[String].right
        unit <- (try { Right(TimeUnit.valueOf(unitString)) } catch {
          case _: IllegalArgumentException => Left(DecodingFailure("FiniteDuration", c.history))
        }).right
      } yield FiniteDuration(length, unit)
    }

  implicit private final val finiteDurationEncoder: Encoder[FiniteDuration] = new Encoder[FiniteDuration] {
    final def apply(a: FiniteDuration): Json =
      Json.fromJsonObject(
        JsonObject(
          "length" -> Json.fromLong(a.length),
          "unit"   -> Json.fromString(a.unit.name)))
  }

  implicit private val codec: Codec[State] = new Codec[State]{
    def apply(c: HCursor): Decoder.Result[CircuitBreaker.State] = 
      c.downField("Closed").downField("failures").as[Int].map(Closed(_))
        .orElse(
          c.downField("HalfOpen").as[Option[Unit]].map(_ => HalfOpen)
        )
        .orElse(
          (
            c.downField("Open").downField("startedAt").as[Long],
            c.downField("Open").downField("resetTimeout").as[FiniteDuration]
          ).mapN(Open(_, _))
        )
    
    def apply(a: CircuitBreaker.State): Json = a match {
      case Closed(failures) => Json.obj{
        "Closed" -> Json.obj("failures" -> failures.asJson)
      }
      case HalfOpen => Json.obj(
        "HalfOpen" -> Json.obj()
      )
      case Open(startedAt, resetTimeout) => Json.obj(
        "Open" -> Json.obj(

            "startedAt" -> startedAt.asJson,
            "resetTimeout" -> resetTimeout.asJson
        )
      )
    }
  }
}