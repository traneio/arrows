package arrows

import java.util.concurrent.ConcurrentLinkedQueue
import scala.reflect.ClassTag

private[arrows] final class ArrayCache[T: ClassTag](arraySize: Int) {

  private final val queue = new ConcurrentLinkedQueue[Array[T]]

  final def acquire = {
    val a = queue.poll()
    if (a == null)
      new Array[T](arraySize)
    else
      a
  }

  final def release(a: Array[T]) =
    queue.offer(a)
}