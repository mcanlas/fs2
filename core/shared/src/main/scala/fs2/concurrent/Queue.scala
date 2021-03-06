package fs2
package concurrent

import cats.{Applicative, Functor}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import fs2.internal.Token

import scala.collection.immutable.{Queue => ScalaQueue}

/** Provides the ability to enqueue elements to a `Queue`. */
trait Enqueue[F[_], A] {

  /**
    * Enqueues one element in this `Queue`.
    * If the queue is `full` this waits until queue is empty.
    *
    * This completes after `a`  has been successfully enqueued to this `Queue`
    */
  def enqueue1(a: A): F[Unit]

  /**
    * Enqueues each element of the input stream to this `Queue` by
    * calling `enqueue1` on each element.
    */
  def enqueue: Sink[F, A] = _.evalMap(enqueue1)

  /**
    * Offers one element in this `Queue`.
    *
    * Evaluates to `false` if the queue is full, indicating the `a` was not queued up.
    * Evaluates to `true` if the `a` was queued up successfully.
    *
    * @param a `A` to enqueue
    */
  def offer1(a: A): F[Boolean]
}

/** Provides the ability to dequeue individual elements from a `Queue`. */
trait Dequeue1[F[_], A] {

  /** Dequeues one `A` from this queue. Completes once one is ready. */
  def dequeue1: F[A]

  /** tries to dequeue element, yields to None if the element cannot be dequeue **/
  def tryDequeue1: F[Option[A]]
}

/** Provides the ability to dequeue chunks of elements from a `Queue` as streams. */
trait Dequeue[F[_], A] {

  /** Dequeue elements from the queue */
  def dequeue: Stream[F, A] =
    dequeueChunk(Int.MaxValue)

  /** Dequeue elements from the queue, size of the chunks dequeue is restricted by `maxSize` */
  def dequeueChunk(maxSize: Int): Stream[F, A]

  /** provides a pipe, that for each dequeue sets the constrain on maximum number of element dequeued */
  def dequeueBatch: Pipe[F, Int, A]
}

/**
  * A Queue of elements. Operations are all nonblocking in their
  * implementations, but may be 'semantically' blocking. For instance,
  * a queue may have a bound on its size, in which case enqueuing may
  * block (be delayed asynchronously) until there is an offsetting dequeue.
  */
trait Queue[F[_], A] extends Enqueue[F, A] with Dequeue1[F, A] with Dequeue[F, A] { self =>

  /**
    * Returns an alternate view of this `Queue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): Queue[F, B] =
    new Queue[F, B] {
      def enqueue1(a: B): F[Unit] = self.enqueue1(g(a))
      def offer1(a: B): F[Boolean] = self.offer1(g(a))
      def dequeue1: F[B] = self.dequeue1.map(f)
      def tryDequeue1: F[Option[B]] = self.tryDequeue1.map(_.map(f))
      def dequeueChunk(maxSize: Int): Stream[F, B] = self.dequeueChunk(maxSize).map(f)
      def dequeueBatch: Pipe[F, Int, B] = self.dequeueBatch.andThen(_.map(f))
    }
}

/**
  * Like [[Queue]], but allows allows signalling of no further enqueues by enqueueing `None`.
  * Optimizes dequeue to minimum possible boxing.
  */
trait NoneTerminatedQueue[F[_], A]
    extends Enqueue[F, Option[A]]
    with Dequeue1[F, Option[A]]
    with Dequeue[F, A] { self =>

  /**
    * Returns an alternate view of this `NoneTerminatedQueue` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): NoneTerminatedQueue[F, B] =
    new NoneTerminatedQueue[F, B] {
      def enqueue1(a: Option[B]): F[Unit] = self.enqueue1(a.map(g))
      def offer1(a: Option[B]): F[Boolean] = self.offer1(a.map(g))
      def dequeue1: F[Option[B]] = self.dequeue1.map(_.map(f))
      def tryDequeue1: F[Option[Option[B]]] = self.tryDequeue1.map(_.map(_.map(f)))
      def dequeueChunk(maxSize: Int): Stream[F, B] = self.dequeueChunk(maxSize).map(f)
      def dequeueBatch: Pipe[F, Int, B] = self.dequeueBatch.andThen(_.map(f))
    }
}

object Queue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.lifo[A])

  /** Creates an unbounded queue that distributed always at max `fairSize` elements to any subscriber. */
  def fairUnbounded[F[_], A](fairSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.lifo[A].transformSelector[Int]((sz, _) => sz.min(fairSize)))

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.boundedLifo(maxSize))

  /** Creates a bounded queue terminated by enqueueing `None`. All elements before `None` are preserved. */
  def boundedNoneTerminated[F[_], A](maxSize: Int)(
      implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    forStrategyNoneTerminated(PubSub.Strategy.closeDrainFirst(Strategy.boundedLifo(maxSize)))

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.circularBuffer(maxSize))

  /** Created a bounded queue that distributed always at max `fairSize` elements to any subscriber. */
  def fairBounded[F[_], A](maxSize: Int, fairSize: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.boundedLifo(maxSize).transformSelector[Int]((sz, _) => sz.min(fairSize)))

  /** Created an unbounded queue terminated by enqueueing `None`. All elements before `None`. */
  def noneTerminated[F[_], A](implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    forStrategyNoneTerminated(PubSub.Strategy.closeDrainFirst(Strategy.lifo))

  /** Creates a queue which allows at most a single element to be enqueued at any time. */
  def synchronous[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    forStrategy(Strategy.synchronous)

  /** Like [[synchronous]], except that any enqueue of `None` will never block and cancels any dequeue operation. */
  def synchronousNoneTerminated[F[_], A](implicit F: Concurrent[F]): F[NoneTerminatedQueue[F, A]] =
    forStrategyNoneTerminated(PubSub.Strategy.closeNow(Strategy.synchronous))

  private[fs2] def headUnsafe[F[_]: Sync, A](chunk: Chunk[A]): F[A] =
    if (chunk.size == 1) Applicative[F].pure(chunk(0))
    else Sync[F].raiseError(new Throwable(s"Expected chunk of size 1. got $chunk"))

  /** Creates a queue from the supplied strategy. */
  private[fs2] def forStrategy[F[_]: Concurrent, S, A](
      strategy: PubSub.Strategy[A, Chunk[A], S, Int]): F[Queue[F, A]] =
    PubSub(strategy).map { pubSub =>
      new Queue[F, A] {

        def enqueue1(a: A): F[Unit] =
          pubSub.publish(a)

        def offer1(a: A): F[Boolean] =
          pubSub.tryPublish(a)

        def dequeue1: F[A] =
          pubSub.get(1).flatMap(headUnsafe[F, A])

        def tryDequeue1: F[Option[A]] = pubSub.tryGet(1).flatMap {
          case Some(chunk) => headUnsafe[F, A](chunk).map(Some(_))
          case None        => Applicative[F].pure(None)
        }
        def dequeueChunk(maxSize: Int): Stream[F, A] =
          Stream.evalUnChunk(pubSub.get(maxSize)).repeat

        def dequeueBatch: Pipe[F, Int, A] =
          _.flatMap(sz => Stream.evalUnChunk(pubSub.get(sz)))
      }
    }

  /** Creates a queue that is terminated by enqueueing `None` from the supplied strategy. */
  private[fs2] def forStrategyNoneTerminated[F[_]: Concurrent, S, A](
      strategy: PubSub.Strategy[Option[A], Option[Chunk[A]], S, Int])
    : F[NoneTerminatedQueue[F, A]] =
    PubSub(strategy).map { pubSub =>
      new NoneTerminatedQueue[F, A] {
        def enqueue1(a: Option[A]): F[Unit] =
          pubSub.publish(a)

        def offer1(a: Option[A]): F[Boolean] =
          pubSub.tryPublish(a)

        def dequeueChunk(maxSize: Int): Stream[F, A] =
          Stream.repeatEval(pubSub.get(maxSize)).unNoneTerminate.flatMap(Stream.chunk)

        def dequeueBatch: Pipe[F, Int, A] =
          _.flatMap(sz => Stream.eval(pubSub.get(sz))).unNoneTerminate.flatMap(Stream.chunk)

        def tryDequeue1: F[Option[Option[A]]] =
          pubSub.tryGet(1).flatMap {
            case None              => Applicative[F].pure(None)
            case Some(None)        => Applicative[F].pure(Some(None))
            case Some(Some(chunk)) => headUnsafe[F, A](chunk).map(a => Some(Some(a)))
          }

        def dequeue1: F[Option[A]] =
          pubSub.get(1).flatMap {
            case None        => Applicative[F].pure(None)
            case Some(chunk) => headUnsafe[F, A](chunk).map(Some(_))
          }
      }
    }

  private[fs2] object Strategy {

    /** Unbounded fifo strategy. */
    def boundedFifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] =
      PubSub.Strategy.bounded(maxSize)(fifo[A])(_.size)

    /** Unbounded lifo strategy. */
    def boundedLifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] =
      PubSub.Strategy.bounded(maxSize)(lifo[A])(_.size)

    /** Strategy for circular buffer, which stores the last `maxSize` enqueued elements and never blocks on enqueue. */
    def circularBuffer[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] =
      unbounded { (q, a) =>
        if (q.size < maxSize) q :+ a
        else q.tail :+ a
      }

    /** Unbounded lifo strategy. */
    def fifo[A]: PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] = unbounded((q, a) => a +: q)

    /** Unbounded fifo strategy. */
    def lifo[A]: PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] = unbounded(_ :+ _)

    /**
      * Strategy that allows at most a single element to be published.
      * Before the `A` is published successfully, at least one subscriber must be ready to consume.
      */
    def synchronous[A]: PubSub.Strategy[A, Chunk[A], (Boolean, Option[A]), Int] =
      new PubSub.Strategy[A, Chunk[A], (Boolean, Option[A]), Int] {
        def initial: (Boolean, Option[A]) = (false, None)

        def accepts(i: A, queueState: (Boolean, Option[A])): Boolean =
          queueState._1 && queueState._2.isEmpty

        def publish(i: A, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
          (queueState._1, Some(i))

        def get(selector: Int,
                queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Option[Chunk[A]]) =
          queueState._2 match {
            case None    => ((true, None), None)
            case Some(a) => ((false, None), Some(Chunk.singleton(a)))
          }

        def empty(queueState: (Boolean, Option[A])): Boolean =
          queueState._2.isEmpty

        def subscribe(selector: Int,
                      queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Boolean) =
          (queueState, false)

        def unsubscribe(selector: Int, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
          queueState
      }

    /**
      * Creates unbounded queue strategy for `A` with configurable append function.
      *
      * @param append function used to append new elements to the queue
      */
    def unbounded[A](append: (ScalaQueue[A], A) => ScalaQueue[A])
      : PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] =
      new PubSub.Strategy[A, Chunk[A], ScalaQueue[A], Int] {

        val initial: ScalaQueue[A] = ScalaQueue.empty

        def publish(a: A, queueState: ScalaQueue[A]): ScalaQueue[A] =
          append(queueState, a)

        def accepts(i: A, queueState: ScalaQueue[A]): Boolean =
          true

        def empty(queueState: ScalaQueue[A]): Boolean =
          queueState.isEmpty

        def get(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Option[Chunk[A]]) =
          if (queueState.isEmpty) (queueState, None)
          else {
            val (out, rem) = queueState.splitAt(selector)
            (rem, Some(Chunk.seq(out)))
          }

        def subscribe(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Boolean) =
          (queueState, false)

        def unsubscribe(selector: Int, queueState: ScalaQueue[A]): ScalaQueue[A] =
          queueState
      }
  }
}

/** Extension of [[Queue]] that allows peeking and inspection of the current size. */
trait InspectableQueue[F[_], A] extends Queue[F, A] {

  /**
    * Returns the element which would be dequeued next,
    * but without removing it. Completes when such an
    * element is available.
    */
  def peek1: F[A]

  /**
    * The time-varying size of this `Queue`.
    * Emits elements describing the current size of the queue.
    * Offsetting enqueues and de-queues may not result in refreshes.
    */
  def size: Stream[F, Int]

  /** Gets the current size of the queue. */
  def getSize: F[Int]
}

object InspectableQueue {

  /** Creates a queue with no size bound. */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(Queue.Strategy.lifo[A])(_.headOption)(_.size)

  /** Creates a queue with the specified size bound. */
  def bounded[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(Queue.Strategy.boundedLifo[A](maxSize))(_.headOption)(_.size)

  /** Creates a queue which stores the last `maxSize` enqueued elements and which never blocks on enqueue. */
  def circularBuffer[F[_], A](maxSize: Int)(implicit F: Concurrent[F]): F[InspectableQueue[F, A]] =
    forStrategy(Queue.Strategy.circularBuffer[A](maxSize))(_.headOption)(_.size)

  private[fs2] def forStrategy[F[_]: Concurrent, S, A](
      strategy: PubSub.Strategy[A, Chunk[A], S, Int]
  )(
      headOf: S => Option[A]
  )(
      sizeOf: S => Int
  ): F[InspectableQueue[F, A]] =
    PubSub(PubSub.Strategy.Inspectable.strategy(strategy)).map { pubSub =>
      new InspectableQueue[F, A] {
        def enqueue1(a: A): F[Unit] = pubSub.publish(a)
        def offer1(a: A): F[Boolean] = pubSub.tryPublish(a)
        def dequeue1: F[A] = pubSub.get(Right(1)).flatMap {
          case Left(s) =>
            Sync[F].raiseError(new Throwable(
              s"Inspectable `dequeue1` requires chunk of size 1 with `A` got Left($s)"))
          case Right(chunk) =>
            Queue.headUnsafe[F, A](chunk)

        }

        def tryDequeue1: F[Option[A]] = pubSub.tryGet(Right(1)).flatMap {
          case None => Applicative[F].pure(None)
          case Some(Left(s)) =>
            Sync[F].raiseError(new Throwable(
              s"Inspectable `dequeue1` requires chunk of size 1 with `A` got Left($s)"))
          case Some(Right(chunk)) =>
            Queue.headUnsafe[F, A](chunk).map(Some(_))
        }

        def dequeueChunk(maxSize: Int): Stream[F, A] =
          Stream
            .evalUnChunk(
              pubSub.get(Right(maxSize)).map { _.right.toOption.getOrElse(Chunk.empty) }
            )
            .repeat

        def dequeueBatch: Pipe[F, Int, A] =
          _.flatMap { sz =>
            Stream
              .evalUnChunk(
                pubSub.get(Right(sz)).map { _.right.toOption.getOrElse(Chunk.empty) }
              )
          }

        def peek1: F[A] =
          Sync[F].bracket(Sync[F].delay(new Token))({ token =>
            def take: F[A] =
              pubSub.get(Left(Some(token))).flatMap {
                case Left(s) =>
                  headOf(s) match {
                    case None    => take
                    case Some(a) => Applicative[F].pure(a)
                  }

                case Right(chunk) =>
                  Sync[F].raiseError(new Throwable(
                    s"Inspectable `peek1` requires chunk of size 1 with state, got: $chunk"))
              }
            take
          })(token => pubSub.unsubscribe(Left(Some(token))))

        def size: Stream[F, Int] =
          Stream
            .bracket(Sync[F].delay(new Token))(token => pubSub.unsubscribe(Left(Some(token))))
            .flatMap { token =>
              Stream.repeatEval(pubSub.get(Left(Some(token)))).flatMap {
                case Left(s)  => Stream.emit(sizeOf(s))
                case Right(_) => Stream.empty // impossible
              }
            }

        def getSize: F[Int] =
          pubSub.get(Left(None)).map {
            case Left(s)  => sizeOf(s)
            case Right(_) => -1
          }
      }
    }
}
