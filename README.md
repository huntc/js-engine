JavaScript Engine
=================

[![Build Status](https://api.travis-ci.org/typesafehub/js-engine.png?branch=master)](https://travis-ci.org/typesafehub/js-engine)

The JavaScript Engine library (jse) provides an [Actor](http://en.wikipedia.org/wiki/Actor_model) based abstraction so that JavaScript code can be 
executed in a browser-less fashion. In-jvm support is provided in the form of [Trireme](https://github.com/apigee/trireme#trireme),
a Node API for [Rhino](https://developer.mozilla.org/en/docs/Rhino). Standalone Rhino is also supported with a RhinoShell environment.
Native JavaScript performance is provided by using
[Common Node](http://olegp.github.io/common-node/),
[node.js](http://nodejs.org/) and
[PhantomJS](http://phantomjs.org/) (these latter 3 are required to be installed separately).

While multiple engines are provided, plugin authors are encouraged to target the Node API. Doing so means that the
engine options generally come down to Trireme and Node, depending on whether in-JVM or native support is required. Trireme
is therefore provided as a default as there should be no JS coding differences between Trireme and Node, and Trireme
requires no manual installation.

Sample usage can be obtained by inspecting the js-engine-tester sub-project. There's a main class that
illustrates essential interactions. Here is a snippet of it:

    val engine = system.actorOf(Node.props(), "engine")
    val f = new File(Main.getClass.getResource("test.js").toURI)
    for (
      result <- (engine ? Engine.ExecuteJs(f, Seq("999"))).mapTo[JsExecutionResult]
    ) yield {
      println(new String(result.output.toArray, "UTF-8"))
      ...

An additional sbt-js-engine sub-project is provided that declares a base for sbt plugins that use the engine.
This sub-project has a separate release cycle to jse itself and could be spun off into its own repo at a later
point in time e.g. if/when Maven/Gradle support is required. The main point here is that the core JavaScript engine
library is not related to sbt at all and should be usable from other build tools.

The library is entirely [reactive](http://www.reactivemanifesto.org/) and uses [Akka](http://akka.io/).

&copy; Typesafe Inc., 2013