def foo2[T <: Matchable](t: T) = t match { case null => () }