/*global process, require */

var args = process.argv;
var console = require("console");

console.error("Options passed: " + args[2]);

/*
 * Return an object with the mandatory problems and results properties.
 * "problems" declares a list of compilation problems that will be reported by our
 * plugin world.
 * "results" maps a source file to an operation result. A result can either be null
 * (signifying an error) or it can be an object containing the filesRead and
 * filesWritten properties if the operation performed successfully. This result data
 * is used for determining whether the operation should occur the next time it is
 * performed.
 */
console.log(
    {
        "problems": [
            {
                "characterOffset": 5,
                "lineContent": "a = 1",
                "lineNumber": 1,
                "message": "Some compilation error message",
                "severity": "error",
                "source": "some.js"
            }
        ],
        "results": [
            {
                "result": {
                    "filesRead": [
                        "some.js"
                    ],
                    "filesWritten": []
                },
                "source": "some.js"
            }
        ]
    }
);