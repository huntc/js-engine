/**
 * Responsible for setting up the Js engine's environment such as stdio.
 */

/*global arguments, ByteString, Charset, engineActor, importPackage, print:true, Packages, Process$EOF$, Process$OutputEvent$, require */

(function (args) {
    "use strict";

    importPackage(Packages.akka.util.ByteString);
    importPackage(Packages.akka.contrib.process);
    importPackage(Packages.java.nio.charset);

    // Override Rhino's print so that it sends stdout back to our actor.
    function sendEngineIoEvent(x, event) {
        engineActor.tell(
            event.MODULE$.apply(
                ByteString.fromArray(x.toString().getBytes(Charset.forName("UTF-8")))
            ),
            null
        );
    }

    print = function (x) {
        sendEngineIoEvent(x, Process$OutputEvent$);
    };

    // Call the Js passed in to be evaluated.
    require(args[0]);

    // Tell the engine that we're done.
    engineActor.tell(Process$EOF$.MODULE$, null);

}(arguments));
