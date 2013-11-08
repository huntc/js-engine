/*var args = require('system').args;
 console.log(args[2]);
 //if (phantom !== undefined) phantom.exit();
 */
importPackage(Packages.com.typesafe.jse);
engineActor.tell(Engine$OutputEvent$.MODULE$.apply("Hi there"), null);
engineActor.tell(Engine$FinalEvent$.MODULE$, null);
