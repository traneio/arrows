package arrows

package object stdlib {
  type Task[+T] = Arrow[Unit, T]
}
