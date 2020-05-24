package cats.effect.interop.java

import _root_.java.util.concurrent.atomic.AtomicInteger
import _root_.java.util.concurrent.CompletableFuture

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.testing.minitest.IOTestSuite
import cats.effect.interop.java.syntax._

import scala.concurrent.duration._

object CompletableFutureSpec extends IOTestSuite {

  def P[A]: IO[CompletableFuture[A]] = IO(new CompletableFuture[A])
  val AT: IO[AtomicInteger]          = IO(new AtomicInteger(0))

  test("delayed value") {
    for {
      p     <- P[Unit]
      fiber <- IO.delay(p.thenApply[Int](_ => 5)).fromFuture.start
      _     = p.complete(())
      i     <- fiber.join
    } yield assert(i === 5)
  }

  test("pure future") {
    for {
      i <- IO.pure(CompletableFuture.completedFuture(10)).fromFuture
    } yield assert(i == 10)
  }

  test("execute side-effects") {
    for {
      p     <- P[Unit]
      c     <- AT
      fiber <- List.fill(10)(IO(p.thenApply[Int](_ => c.incrementAndGet()))).traverse(_.fromFuture).start
      _     <- IO(p.complete(()))
      as    <- fiber.join
    } yield {
      assert(as.sorted === (1 to 10).toList)
      assert(c.get() === 10)
    }
  }

  test("cancel the underlying future") {
    for {
      c      <- AT
      pa     <- P[Unit]
      pb     <- Deferred[IO, String]
      a      = IO(pa.thenApply[Int](_ => c.incrementAndGet())).fromFuture
      b      = pb.get
      fiber  <- IO.race(a, b).start
      _      <- pb.complete("OK") >> IO.sleep(100.millis) >> IO(pa.complete(()))
      result <- fiber.join
    } yield {
      assert(c.get() === 0)                // side-effect never run
      assert(result === "OK".asRight[Int]) // Deferred completed first
    }
  }

  test("execute sync IO[A]") {
    IO(assert(IO(1).unsafeRunAsyncJ.get() === 1))
  }

  test("execute async IO[A]") {
    IO(assert(IO.sleep(100.millis).as(1).unsafeRunAsyncJ.get === 1))
  }

  test("cancel IO") {
    for {
      c        <- AT
      deferred <- Deferred[IO, Unit]
      fa       = deferred.get.attempt >> IO(c.incrementAndGet())
      f        = fa.unsafeRunAsyncJ
      _        = f.cancel(true)
      _        <- deferred.complete(())
    } yield {
      assert(c.get() === 0)
    }
  }

}
