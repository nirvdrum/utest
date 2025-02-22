package utest.framework

import scala.util.{Random, Success, Failure, Try}
import scala.concurrent.duration.Deadline


import scala.language.experimental.macros
import scala.concurrent.{Await, Future, ExecutionContext}
import concurrent.duration._
import utest.util.Tree
import utest.PlatformShims
import utest.{NoSuchTestException, SkippedOuterFailure}

object Test{
  /**
   * Creates a test from a set of children, a name a a [[TestThunKTree]].
   * Generally called by the [[TestSuite.apply]] macro and doesn't need to
   * be called manually.
   */
  def create(tests: (String, (String, TestThunkTree) => Tree[Test])*)
            (name: String, testTree: TestThunkTree): Tree[Test] = {
    new Tree(
      new Test(name, testTree),
      tests.map{ case (k, v) => v(k, testTree) }
    )
  }
}

/**
 * Represents the metadata around a single test in a [[TestTreeSeq]]. This is
 * a pretty simple data structure, as much of the information related to it
 * comes contextually when traversing the [[utest.framework.TestTreeSeq]] to reach it.
 */
case class Test(name: String, TestThunkTree: TestThunkTree)

/**
 * Extension methods on `TreeSeq[Test]`
 */
class TestTreeSeq(tests: Tree[Test]) {
  /**
   * Runs this `Tree[Test]` asynchronously and returns a `Future` containing
   * the tree of the results.
   *
   * @param onComplete Called each time a single [[Test]] finishes
   * @param path The integer path of the current test in its [[TestThunkTree]]
   * @param strPath The path to the current test
   * @param outerError Whether or not an outer test failed, and this test can
   *                   be failed immediately without running
   * @param ec Used to
   */
  def runAsync(onComplete: (Seq[String], Result) => Unit,
               path: Seq[Int],
               strPath: Seq[String] = Nil,
               outerError: Option[SkippedOuterFailure] = None)
              (implicit ec: ExecutionContext): Future[Tree[Result]] = {

    PlatformShims.flatten(Future{
      val start = Deadline.now
      val tryResult =
        outerError.fold(Try(tests.value.TestThunkTree.run(path.toList)))(Failure(_))

      val thisError = tryResult match{
        case Success(_) => None
        case Failure(e: SkippedOuterFailure) => Some(e)
        case Failure(e) => Some(SkippedOuterFailure(strPath, e))
      }

      val childRuns =
        tests.children
          .zipWithIndex.map{ case (v, i) =>
          v.runAsync(onComplete, path :+ i, strPath :+ v.value.name, thisError)
        }

      val temp = childRuns.foldLeft(Future(List.empty[Tree[Result]])){
        case (a, b) => PlatformShims.flatten(a.map(a => b.map(b => a :+ b)))
      }

      val sequenced = temp.map{ results =>
        val end = Deadline.now
        val result = Result(tests.value.name, tryResult, end.time.toMillis - start.time.toMillis)
        onComplete(strPath, result)
        new Tree(
          result,
          results
        )
      }

      sequenced
    })
  }

  def resolve(testPath: Seq[String]) = {
    val indices = collection.mutable.Buffer.empty[Int]
    var current = tests
    var strings = testPath.toList
    while(!strings.isEmpty){
      val head :: tail = strings
      strings = tail
      val index = current.children.indexWhere(_.value.name == head)
      indices.append(index)
      if (!current.children.isDefinedAt(index)){
        throw NoSuchTestException(testPath:_*)
      }
      current = current.children(index)
    }
    (indices, current)
  }

  def run(onComplete: (Seq[String], Result) => Unit = (_, _) => (),
          strPath: Seq[String] = Nil,
          testPath: Seq[String] = Nil)
         (implicit ec: ExecutionContext): Tree[Result] = {

    val (indices, current) = resolve(testPath)
    val future = current.runAsync(onComplete, indices, strPath)

    PlatformShims.await(future)
  }
}

object TestThunkTree{
  def create(inner: => (Any, Seq[TestThunkTree])) = new TestThunkTree(inner)
}

/**
 * A tree of nested lexical scopes that accompanies the tree of tests. This
 * is separated from the tree of metadata in [[TestTreeSeq]] in order to allow
 * you to query the metadata without executing the tests. Generally created by
 * the [[TestSuite]] macro and not instantiated manually.
 */
class TestThunkTree(inner: => (Any, Seq[TestThunkTree])){
  /**
   * Runs the test in this [[TestThunkTree]] at the specified `path`. Called
   * by the [[TestTreeSeq.run]] method and usually not called manually.
   */
  def run(path: List[Int]): Any = {
    path match {
      case head :: tail =>
        val (res, children) = inner
        children(head).run(tail)
      case Nil =>
        val (res, children) = inner
        res
    }
  }
}

/**
 * A single test's result after execution. Any exception thrown or value
 * returned by the test is stored in `value`. The value returned can be used
 * in another test, which adds a dependency between them.
 */
case class Result(name: String,
                  value: Try[Any],
                  milliDuration: Long)
