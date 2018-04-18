# Arrows - High-performance Arrow and Task in Scala

[![Build Status](https://travis-ci.org/traneio/arrows.svg?branch=master)](https://travis-ci.org/traneio/arrows)
[![codecov.io](https://codecov.io/github/traneio/arrows/coverage.svg?branch=master)](https://codecov.io/github/traneio/arrows?branch=master)
[![Join the chat at https://gitter.im/traneio/arrows](https://img.shields.io/badge/gitter-join%20chat-green.svg)](https://gitter.im/traneio/arrows?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.trane/arrows_2.12/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cio.trane%20arrows)
[![Javadocs](https://www.javadoc.io/badge/io.trane/arrows_2.12.svg)](https://www.javadoc.io/doc/io.trane/arrows_2.12)

## Getting started

This library provides `Arrow` and `Task` implementations in two flavors:

`arrows-stdlib`: built on top of the Scala Future, without external dependencies. This module also provides ScalaJS artifacts.

```
libraryDependencies ++= Seq(
  "io.trane" %% "arrows-stdlib" % "0.1.17"
)
```

`arrows-twitter`: built on top of the Twitter Future with the `twitter-util` dependency.

```
libraryDependencies ++= Seq(
  "io.trane" %% "arrows-twitter" % "0.1.17"
)
```

Both implementations have similar behavior, but they mirror the interface of the underlying `Future` to make the migration easier.

## Overview

This library is inspired by the paper [Generalizing Monads to Arrows](http://www.cse.chalmers.se/~rjmh/Papers/arrows.pdf), which introduces `Arrow`s as a way to express computations statically. For instance, this monadic computation:

```scala
import com.twitter.util.Future

def callServiceA(i: Int) = Future.value(i * 2) // replace by service call
def callServiceB(i: Int) = Future.value(i + 1) // replace by service call

callServiceA(1).flatMap { r =>
  callServiceB(r)
}
````

can't be fully inspected statically. It's possible to determine that `callServiceA` will be invoked, but only after running `callServiceA` it's possible to identify that `callServiceB` will be invoked since there's a data dependency.

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

Additionally, it has a companion object that has methods similar to the ones provided by `Future`.

Static computations expressed using `Arrow`s have better performance since they avoid many allocations at runtime, but `Task` can also be as a standalone solution without using `Arrow` directly. It's equivalent to the `IO` and `Task` implementations provided by libraries like Monix, Scalaz 8, and Cats Effect.

## Using `Arrow`

Unlike the `Task`, `Arrow`'s' companion object has only a few methods to create new instances. `Arrow`s can be created based on an initial identity arrow through `Arrow.apply`

```scala
val identityArrow: Arrow[Int, Int] = Arrow[Int]
```

The identity arrow just returns its input, but is useful as a starting point to compose new arrows:

```scala
val stringify: Arrow[Int, String] = Arrow[Int].map(_.toString)
```

Additionaly, `Arrow` provides an `apply` method that produces a `Task`, which is the expected return type of a `flatMap` function:

```scala
val nonZeroStringify: Arrow[Int, String] =
  Arrow[Int].flatMap {
    case i if i < 0 => Task.value("")
    case i          => stringify(i)
  }
```

An interesting scenario is recursion with `Arrow`. For this purpose, it's possible to use `Arrow.recursive`:

```scala
val sum =
  Arrow.recursive[List[Int], Int] { self =>
    Arrow[List[Int]].flatMap {
      case Nil          => Task.value(0)
      case head :: tail => self(tail).map(_ + head)
    }
  }
```

Note that `self` is a reference to the `Arrow` under creation.

Once the arrow is created, it can be reused for multiple runs:

```scala
val result1: Future[Int] = sum.run(List(1, 2))
val result2: Future[Int] = sum.run(List(1, 2, 4))
```

For best performance, keep arrows as `val`s in a scope that allows reuse

## Using `Task`

`Task` can be used as a standalone solution similar to the `IO` and `Task` implementations in libraries like Monix, Scalaz 8, and Cats Effect. Their interface mirror the interface of the underlying `Future` implementation.

Even though `Task` is similar to `Future`, it has a different execution mechanism. `Future`s are strict and start to execute once created. `Task` only describes a computation that will eventually execute when executed:

```scala
val f = Future.value(1).map(println) // prints 1 immediatelly
val t = Task.value(1).map(println) // doesn't print anything during Task creation
t.run // prints 1
```

Tricks like saving `Future`s with `val`s for parallelism doesn't work with `Task`:

```scala

val f1: Future[Int] = Future.value(1) // replace by an async op
val f2: Future[Int] = Future.value(2) // replace by an async op

// at this point both futures are running in parallel, even though
// the second future is only used within the `flatMap` function:
f1.flatMap { i =>
  f2.map(_ + i)
}

val t1: Task[Int] = Future.value(1) // replace by an async op
val t2: Task[Int] = Future.value(2) // replace by an async op

// at this point t1 and t2 are not running, so t2 will only run 
// when t1 finishes and the `flatMap` function is called:
t1.flatMap { i =>
  t2.map(_ + i)
}

```


## Migrating from `Future`

Given that `Task` has an interface similar to `Future`, it's possible to use [Scalafix](https://scalacenter.github.io/scalafix) to do most of the migration. Suggested steps:

#### 1. Install Scalafix

See the [Scalafix documentation](https://scalacenter.github.io/scalafix/docs/users/installation) for all installation options. An easy way is adding the scalafix plugin to your sbt configuration:

```sh
echo -e '\n\n addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.5.10")' >> project/plugins.sbt
```

#### 2. Run a symbol rewrite from `Future` to `Task`

```sh
# for the Twitter Future
sbt -J-XX:MaxMetaspaceSize=512m 'scalafixCli --rules replace:com.twitter.util.Future/arrows.twitter.Task

# for the Scala Future
sbt -J-XX:MaxMetaspaceSize=512m 'scalafixCli --rules replace:scala.concurrent.Future/arrows.stdlib.Task
```

The metaspace option is important to run scalafix since sbt's default is too low.

#### 3. Review changes

Look for places where the change from strict to lazy might change the behavior. See the "Using `Task`" section to understand the difference.

At this point, it's reasonable to pause the migration and test the system to find potential issues with the migration.

#### 4. Fix deprecation warnings

There are deprecated implicit conversions from/to `Arrow`/`Future`. They were introduced to make the migration easier. Fix the deprecation warnings by calling the conversion methods directly and making sure that `Future`s are created within the `Task` execution, not outside.

#### 5. Identify arrows

As an additional step for even better performance, identify methods that can become `Arrow`s and convert them.

## Maintainers

- @fwbrasil (creator)
- you? :)

## Code of Conduct

Please note that this project is released with a Contributor Code of Conduct. By participating in this project you agree to abide by its terms. See [CODE_OF_CONDUCT.md](https://github.com/traneio/arrows/blob/master/CODE_OF_CONDUCT.md) for details.

## License

See the [LICENSE](https://github.com/traneio/arrows/blob/master/LICENSE.txt) file for details.


