package arrows

package object twitter {
  type Task[+T] = Arrow[Unit, T]
}
