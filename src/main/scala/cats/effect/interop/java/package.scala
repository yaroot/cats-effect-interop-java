package cats.effect.interop

import _root_.java.util.concurrent.{CompletableFuture, CompletionException, CancellationException}

import cats.syntax.all._
import cats.effect._

package object java {
  def fromFuture[F[_], A](f: F[CompletableFuture[A]])(implicit F: ConcurrentEffect[F]): F[A] = {
    f.flatMap { future =>
      F.cancelable { cb =>
        future.handle {
          case (a, null)                                         => cb(a.asRight)
          case (_, _: CancellationException)                     => ()
          case (_, e: CompletionException) if e.getCause ne null => cb(e.asLeft)
          case (_, e: Throwable)                                 => cb(e.asLeft)
        }
        F.delay {
          val _ = future.cancel(true)
        }
      }
    }
  }

  def unsafeRunAsyncJ[F[_], A](f: F[A])(implicit F: ConcurrentEffect[F]): CompletableFuture[A] = {
    new CompletableFuture[A] { self =>
      val cancelIO: CancelToken[F] = F
        .runCancelable(f) { x =>
          IO(x.fold(self.completeExceptionally, self.complete)).void
        }
        .unsafeRunSync()

      override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
        F.toIO(cancelIO).unsafeRunAsyncAndForget()
        super.cancel(mayInterruptIfRunning)
      }
    }

  }

  object syntax {
    implicit class catsEffectJavaSyntaxUnsafeRun[F[_], A](private val f: F[A]) extends AnyVal {
      def unsafeRunAsyncJ(implicit F: ConcurrentEffect[F]): CompletableFuture[A] = {
        java.unsafeRunAsyncJ(f)
      }
    }

    implicit class catsEffectJavaSyntaxFromFuture[F[_], A](private val f: F[CompletableFuture[A]]) extends AnyVal {
      def fromFuture(implicit F: ConcurrentEffect[F]): F[A] = {
        java.fromFuture(f)
      }
    }
  }
}
