var args = require('system').args;
console.log(args[1]);
if (phantom !== undefined) phantom.exit();