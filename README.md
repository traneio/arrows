# Arrows - High-performance Arrow and Task in Scala

[![Build Status](https://travis-ci.org/traneio/arrows.svg?branch=master)](https://travis-ci.org/traneio/arrows)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/36ab84c7ff43480489df9b7312a4bdc1)](https://www.codacy.com/app/fwbrasil/quill)
[![codecov.io](https://codecov.io/github/traneio/arrows/coverage.svg?branch=master)](https://codecov.io/github/traneio/arrows?branch=master)
[![Join the chat at https://gitter.im/traneio/arrows](https://img.shields.io/badge/gitter-join%20chat-green.svg)](https://gitter.im/traneio/arrows?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.trane/arrows_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.trane/arrows_2.12)
[![Javadocs](https://www.javadoc.io/badge/io.trane/arrows_2.12.svg)](https://www.javadoc.io/doc/io.trane/arrows_2.12)

This library provides `Arrow` and `Task` implementations in two flavors:

- `arrows-stdlib`: built on top of the Scala Future, without external dependencies. This module also provides ScalaJS artifacts.

```
libraryDependencies ++= Seq(
  "io.trane" %%% "arrows-stdlib" % "0.1.0-SNAPSHOT"
)
```

- `arrows-twitter`: built on top of the Twitter Future with the `twitter-util` dependency.

```
libraryDependencies ++= Seq(
  "io.trane" %% "arrows-twitter" % "0.1.12"
)
```

Both implementations have similar behavior, but they mirror the interface of the underlying Future to make the migration easier.

The library is inspired by the paper [Generalizing Monads to Arrows](http://www.cse.chalmers.se/~rjmh/Papers/arrows.pdf). It introduces Arrows as a way to express computations statically. For instance, this monadic computation:

```scala
import com.twitter.util.Future

def callServiceA(i: Int) = Future.value(i * 2) // replace by service call
def callServiceB(i: Int) = Future.value(i + 1) // replace by service call

callServiceA(1).flatMap { r =>
  callServiceB(r)
}
````

can't be fully inspected statically. It's possible to determine that `callServiceA` will be invoked, but only after running `callServiceA` it's possible to determine that `callServiceB` will be invoked since there's a data dependency.

Arrows are functions that can be composed and reused for multiple executions:

```scala
import arrows.twitter._

val callServiceA = Arrow[Int].map(_ * 2) // replace by service call
val callServiceB = Arrow[Int].map(_ + 1) // replace by service call

val myArrow: Arrow[Int, Int] = 
  callServiceA.andThen(callServiceB)

val result: Future[Int] = myArrow.run(1)
```

The paper also introduces the `first` operator to deal with scenarios like branching where expressing the computation using `Arrow`s is challenging. It's an interesting technique that simulates a `flatMap` using tuples and joins/zips, but introduces considerable code complexity.

This library takes a different approach. Instead of avoiding `flatMap`s, it introduces a special case of `Arrow` called `Task`, which is a regular monad. `Task` is based on `ArrowApply` also introduced by the paper.

Users can easily express computations that are static using arrows and apply them within `flatMap` functions to produce `Task`s:

```scala
val myArrow =
  callServiceA.flatMap {
     case 0 => Task.value(0)
     case i => callServiceB(i)
  }

val result: Future[Int] = myArrow.run(1)
```

It's a mixed approach: computations that can be easily expressed statically don't need `flatMap` and `Task`, which are normally most of the cases. Only the portion of the computations that require some dynamicity needs to incur the price of using `flatMap` and `Task`.

`Task` is an `Arrow` without inputs. It's declared as a type alias:

```scala
type Task[+T] = Arrow[Unit, T]
```

Additionally, it has a companion object that has methods similar to the ones provided by the `Future` companion object.

Static computations expressed using `Arrow`s have better performance since they avoid many allocations at runtime, but `Task` can also be as a standalone solution without using `Arrow` directly. It's equivalent to the `IO` and `Task` implementations provided by libraries like Monix, Scalaz 8, and Cats Effect.

The `Task` interface mirrors the methods provided by `Future`. For more details on the available operations, please see the scaladocs. 

**Note that, even though `Task` has an API very similar to `Future`, it has a different execution mechanism. `Future`s are strict and start executing once created. `Task`s only describe computations without running them, and are executed only when the user calls `run`.**