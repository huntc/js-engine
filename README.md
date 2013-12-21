JavaScript Engine
=================

The JavaScript Engine library (jse) provides an [Actor](http://en.wikipedia.org/wiki/Actor_model) based abstraction so that JavaScript code can be 
executed in a browser-less fashion. In-jvm support is provided in the form of [Ringo](http://ringojs.org/) and
[Rhino](https://developer.mozilla.org/en/docs/Rhino). Native JavaScript performance is provided by using
[Common Node](http://olegp.github.io/common-node/),
[node.js](http://nodejs.org/) and
[PhantomJS](http://phantomjs.org/) (these are required to be installed).

Sample usage can be obtained by inspecting the js-engine-tester sub-project. There's a main class that
illustrates essential interactions. Here is a snippet of it:

    val engine = system.actorOf(Ringo.props(), "engine")
    val f = new File(Main.getClass.getResource("test.js").toURI)
    for (
      result <- (engine ? Engine.ExecuteJs(f, Seq("999"))).mapTo[JsExecutionResult]
    ) yield {
      println(new String(result.output.toArray, "UTF-8"))
      ...

An additional js-engine-sbt sub-project is provided that declares the a base for sbt plugins that use the engine.
This sub-project has a separate release cycle to jse itself and could be spun off into its own repo at a later
point in time e.g. if/when Maven/Gradle support is required. The main point here is that the core JavaScript engine
library is not related to sbt at all and should be usable from other build tools.

The library is entirely [reactive](http://www.reactivemanifesto.org/) and uses [Akka](http://akka.io/).

&copy; Typesafe Inc., 2013