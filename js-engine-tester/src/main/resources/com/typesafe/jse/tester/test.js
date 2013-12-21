var print;
try {
    print = require("system").print;
} catch (e) {
}

var error;
try {
    error = require("console").error;
} catch (e) {
    error = print;
}

print("Hi there");
print("and again!");

error("Some error.");