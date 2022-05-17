
import com.typesafe.tools.mima.core._

object MiMaFilters {
  val Library: Seq[ProblemFilter] = Seq(
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals.getStaticOffset"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals.objCAS"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.evaluating"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.getStaticOffset"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.nullValue"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.objCas"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.waiting"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.waitingAwaitRelease"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("scala.runtime.LazyVals#Names.waitingRelease"),
    ProblemFilters.exclude[MissingClassProblem]("scala.runtime.LazyVals$Evaluating$"),
    ProblemFilters.exclude[MissingClassProblem]("scala.runtime.LazyVals$NULL$"),
    ProblemFilters.exclude[MissingClassProblem]("scala.runtime.LazyVals$Waiting"),
    ProblemFilters.exclude[MissingFieldProblem]("scala.runtime.LazyVals.Evaluating"),
    ProblemFilters.exclude[MissingFieldProblem]("scala.runtime.LazyVals.NULL"),
  )
}
