// Symlink this repository into the SuperCollider extensions folder.
// Evaluate the block below, then recompile the class library
// (Language -> Recompile Class Library, or ctrl/cmd + shift + L).
//
// This is the route for a clone you want to keep editing. If you would
// rather have it installed and updated for you, do not use this file -
// install it as a quark instead, from a fresh sclang:
//
//   Quarks.install("https://github.com/fracnesco/sc-recorder.git");
//   thisProcess.recompile;
//
// One or the other, not both: two copies on the class path is a
// duplicate class error at compile time.
//
// ln -s is macOS and Linux. On Windows, use the quark, or copy this
// folder into Platform.userExtensionDir by hand - .openOS opens it.
//
// BusRecorder depends on nothing. If BusManager happens to be installed
// as well, sources can be added by bus name - BusRecorder.add(\drums) -
// and follow the name from then on. Neither repository needs the other
// to compile.

(
var src = PathName(thisProcess.nowExecutingPath).pathOnly;
var dst = Platform.userExtensionDir +/+ "BusRecorder";

// drop the trailing slash, ln -s does not like it
if (src.endsWith("/")) { src = src.drop(-1) };

case
{ File.exists(dst) } {
	("BusRecorder: % already exists - remove it first if you want to relink."
		.format(dst)).warn;
}
{ true } {
	File.mkdir(Platform.userExtensionDir);
	("ln -s" + src.shellQuote + dst.shellQuote).unixCmd({ |exit|
		if (exit == 0) {
			"BusRecorder: linked".postln;
			("   " ++ src).postln;
			("-> " ++ dst).postln;
			"".postln;
			"Now recompile the class library: ctrl/cmd + shift + L".postln;
		} {
			("BusRecorder: linking failed with exit code" + exit).warn;
		};
	});
};
)


// ---------------------------------------------------------------- verify
(
var missing = [\BusRecorder, \BusRecorderSource, \BusRecorderGUI]
	.select { |sym| sym.asClass.isNil };

if (missing.notEmpty) {
	("BusRecorder is not compiled - % is missing."
		"\n  Recompile the class library, ctrl/cmd + shift + L, and check the"
		"\n  post window for compile errors.".format(missing.join(", "))).warn;
} {
	"BusRecorder is installed.".postln;
	"  sources: % - %".format(BusRecorder.size, BusRecorder.names.join(", ")).postln;
	"  files go to: %".format(BusRecorder.dir).postln;
	"  layouts: % in %".format(BusRecorder.layouts.size, BusRecorder.layoutDir).postln;
	"  window: BusRecorder.gui".postln;
	"  help:   BusRecorder, then cmd+d".postln;
	if (\BusManager.asClass.notNil) {
		"  BusManager is installed too - sources can be added by name: BusRecorder.add(\\drums)"
			.postln;
	};
};
)


// ------------------------------------------------------------- uninstall
(
var link = Platform.userExtensionDir +/+ "BusRecorder";
case
{ File.type(link) == \symlink } {
	File.delete(link);
	("Removed %."
		"\n  Recompile the class library to finish.".format(link)).postln;
}
{ File.type(link) == \not_found } {
	"Nothing to remove at %.".format(link).postln;
}
{ true } {
	"% is not a link, leaving it alone.".format(link).warn;
};
)
