var log;
try {
    log = require("console").log;
} catch (e) {
    log = print;
}

log("Hi there");
log("and again!");
