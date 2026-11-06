// =====================================================================
// BusRecorder - record any set of buses to disk, and a window to do it from
//
// Server.record takes one bus range and writes one file. A session
// usually wants more than that: the drums bus and the reverb bus and
// the main out, each in its own file, all starting on the same sample.
// This keeps a list of sources - buses, by index or by BusManager name -
// and writes them however you ask:
//
//   BusRecorder.add(\main, 0, 2);          // bus 0, two channels
//   BusRecorder.add(\drums, \drums);       // a BusManager name, followed
//   BusRecorder.addIndex(16, 2);           // a bare index, called bus16
//
//   BusRecorder.mode = \sources;           // one file per source
//   BusRecorder.fileName = "take";
//   BusRecorder.record;                    // take_001_main.wav, take_001_drums.wav ...
//   BusRecorder.stop;
//
//   BusRecorder.gui;                       // add, arm, meter, listen, record
//
// Four ways of splitting a take into files, set with `mode`:
//
//   \single    every channel of every source in one multichannel file
//   \pairs     the same channels, cut into stereo files two at a time
//   \sources   one file per source, however many channels it has
//   \mono      one file per channel
//
// A file is named after the take - name, take number - with the sources
// in it appended: by name, or by bus index when `label` is \index. A
// stereo pair that is half of a four channel source says which half.
//
// Everything is class side: one recorder per session, the way there is
// one server. Sources are kept in an Array because the window lists them
// in the order they were added. BusManager is looked up as
// \BusManager.asClass and never referenced directly - without it the
// recorder still works, by index.
//
// See BusRecorderSource for what one source is.
// =====================================================================

BusRecorder {
	classvar <sources;					// BusRecorderSource, in the order they were added
	classvar server;					// read through *server, which fills it in lazily
	classvar <guiObj;
	classvar <metering = false;
	classvar <monitoring;				// the source being listened to, nil if none
	classvar <monitorOut = 0, <monitorAmp = 0.5;
	// how the take is written
	classvar <mode = \single, <label = \name, <numbering = \take;
	classvar dir, <fileName = "rec", <headerFormat = "wav", <sampleFormat = "int24";
	classvar <bufSize = 65536;
	// the take in progress, or the last one
	classvar <recording = false, <paused = false, <files, <lastDuration = 0;
	classvar recPlan, startTime, pauseTime, pausedTotal, stopTask;
	classvar group, monitorSynth, levelFunc, defs, idCounter, hooksAdded, bm;
	classvar bootFunc, treeFunc, quitFunc;

	*initClass {
		sources = [];
		files = [];
		defs = IdentityDictionary.new;
		idCounter = 0;
		hooksAdded = false;
	}

	// Server.default is read the first time it is needed, not in initClass,
	// where the class initialisation order does not promise it exists yet.
	*server { ^server ?? { server = Server.default } }

	*server_ { |aServer|
		if (aServer === this.server) { ^this };
		if (recording) { this.stop };
		this.prRemoveHooks;
		this.clear;
		server = aServer;
		^this
	}

	*modes { ^[\single, \pairs, \sources, \mono] }
	*labels { ^[\name, \index] }
	*numberings { ^[\take, \timestamp, \none] }
	*headerFormats { ^["wav", "aiff", "caf"] }
	*sampleFormats { ^["int16", "int24", "float"] }

	// ------------------------------------------------------------- sources

	// The general door. What `bus` is decides how the source is found:
	//   an Integer      - a bus index, with `channels` (2 by default)
	//   a Symbol/String - a BusManager name, followed from then on
	//   a Bus           - read where it is
	//   nil             - `name` is itself a BusManager name
	// Re-using a name replaces what was there, so re-evaluating a setup
	// block does not pile up sources.
	*add { |name, bus, channels|
		case
		{ bus.isNil } { ^this.addName(name, name, channels) }
		{ bus.isKindOf(Bus) } { ^this.addBus(bus, name, channels) }
		{ bus.isNumber } { ^this.addIndex(bus, channels ? 2, name) }
		{ bus.isKindOf(Symbol) or: { bus.isKindOf(String) } } {
			^this.addName(bus, name, channels)
		}
		{ true } {
			("BusRecorder: add wants an index, a name or a Bus, got %"
				.format(bus.asCompileString)).warn;
			^nil
		}
	}

	// a bare index: hardware channels, or a bus you keep track of yourself
	*addIndex { |index, channels = 2, name|
		var src;
		this.prEnsureSetup;
		index = index.asInteger.max(0);
		src = this.prMake(name ?? { "bus" ++ index }, \index);
		src.prSetBus(Bus(\audio, index, channels.asInteger.max(1), this.server));
		^this.prPut(src)
	}

	// A BusManager name. The source is resolved now and again whenever
	// BusManager says its buses changed, so it follows the name. Without
	// BusManager, or with a name it does not know, the source is kept but
	// unresolved - it shows a ? in the window and is left out of a take.
	*addName { |busName, name, channels|
		var src;
		this.prEnsureSetup;
		src = this.prMake(name ? busName, \name);
		src.prSetBusName(busName);
		src.prSetChannels(channels !? { channels.asInteger.max(1) });
		this.prResolve(src, true);
		^this.prPut(src)
	}

	// a Bus something else made - a module's tapBus, an Ndef's bus
	*addBus { |bus, name, channels|
		var src;
		if (bus.isKindOf(Bus).not) {
			("BusRecorder: addBus needs a Bus, got %".format(bus.asCompileString)).warn;
			^nil
		};
		if (bus.rate != \audio) {
			("BusRecorder: % is a control bus - there is nothing to record"
				.format(bus)).warn;
			^nil
		};
		this.prEnsureSetup;
		src = this.prMake(name ?? { "bus" ++ bus.index }, \bus);
		src.prSetChannels(channels !? { channels.asInteger.max(1) });
		src.prSetBus(bus);
		^this.prPut(src)
	}

	*remove { |name|
		var src = this.at(name);
		if (src.isNil) {
			("BusRecorder: there is no source called %".format(name.asCompileString)).warn;
			^nil
		};
		this.prDrop(src);
		sources = sources.reject { |s| s === src };
		this.changed(\sources);
		^src
	}

	*rename { |from, to|
		var src = this.at(from);
		to = to.asSymbol;
		if (src.isNil) {
			("BusRecorder: there is no source called %".format(from.asCompileString)).warn;
			^nil
		};
		if (to == src.name) { ^src };
		if (this.at(to).notNil) {
			("BusRecorder: the name % is already taken".format(to.asCompileString)).warn;
			^nil
		};
		src.prSetName(to);
		this.changed(\sources);
		^src
	}

	// Whether a source goes into the next take. Disarming keeps it in the
	// list, metered and listenable, just not written.
	*arm { |name, flag = true|
		var src = this.at(name);
		if (src.isNil) {
			("BusRecorder: there is no source called %".format(name.asCompileString)).warn;
			^nil
		};
		src.prSetArmed(flag);
		this.changed(\sources);
		^src
	}

	*disarm { |name| ^this.arm(name, false) }

	*isArmed { |name| ^this.at(name) !? (_.armed) ? false }

	// Empty the list. A take in progress carries on - its files were
	// planned when it started - but the meters and the monitor stop.
	*clear {
		this.listenOff;
		sources.copy.do { |s| this.prDrop(s) };
		sources = [];
		this.changed(\sources);
		^this
	}

	// ---------------------------------------------------------------- lookup

	*at { |name|
		if (name.isKindOf(Symbol).not and: { name.isKindOf(String).not }) { ^nil };
		name = name.asSymbol;
		^sources.detect { |s| s.name == name }
	}

	*includes { |name| ^this.at(name).notNil }

	*names { ^sources.collect(_.name) }

	*size { ^sources.size }

	// the loudest channel of a source right now - 0 unless metering is on
	*level { |name| ^this.at(name) !? (_.level) ? 0 }

	// -------------------------------------------------------------- settings

	*mode_ { |sym|
		sym = sym.asSymbol;
		if (this.modes.includes(sym).not) {
			("BusRecorder: mode is one of % - not %"
				.format(this.modes.join(", "), sym.asCompileString)).warn;
			^this
		};
		mode = sym;
		this.changed(\settings);
		^this
	}

	*label_ { |sym|
		sym = sym.asSymbol;
		if (this.labels.includes(sym).not) {
			("BusRecorder: label is one of % - not %"
				.format(this.labels.join(", "), sym.asCompileString)).warn;
			^this
		};
		label = sym;
		this.changed(\settings);
		^this
	}

	*numbering_ { |sym|
		sym = sym.asSymbol;
		if (this.numberings.includes(sym).not) {
			("BusRecorder: numbering is one of % - not %"
				.format(this.numberings.join(", "), sym.asCompileString)).warn;
			^this
		};
		numbering = sym;
		this.changed(\settings);
		^this
	}

	// Where the files go: SuperCollider's own recordings folder unless told
	// otherwise. Made when the first take starts, not before.
	*dir { ^dir ?? { thisProcess.platform.recordingsDir } }

	*dir_ { |path|
		dir = path !? { path.asString.standardizePath };
		this.changed(\settings);
		^this
	}

	// the first part of every file name; anything empty falls back on "rec"
	*fileName_ { |str|
		str = (str ? "").asString.stripWhiteSpace;
		fileName = if (str.isEmpty) { "rec" } { str };
		this.changed(\settings);
		^this
	}

	*headerFormat_ { |str|
		headerFormat = (str ? "wav").asString.toLower;
		this.changed(\settings);
		^this
	}

	*sampleFormat_ { |str|
		sampleFormat = (str ? "int24").asString.toLower;
		this.changed(\settings);
		^this
	}

	// frames of disk buffer per file, as Server.recBufSize; a power of two
	*bufSize_ { |frames|
		bufSize = (frames ? 65536).asInteger.nextPowerOfTwo.max(8192);
		^this
	}

	// ------------------------------------------------------------- the plan

	// Which channels go into which file: a list of files, each a list of
	// [source, channel] pairs in the order they will be written. Only armed,
	// resolved sources take part.
	*plan {
		var active = sources.select(_.isRecordable);
		var chans = active.collect { |s| s.numChannels.collect { |i| [s, i] } }.flatten(1);
		if (chans.isEmpty) { ^[] };
		^switch(mode,
			\single, { [chans] },
			\pairs, { chans.clump(2) },
			\sources, { active.collect { |s| chans.select { |c| c[0] === s } } },
			\mono, { chans.collect { |c| [c] } }
		)
	}

	// the files the next take would write, so you can check before you press
	*nextPaths { ^this.prPathsFor(this.plan) }

	// the take number the next take gets: one more than the highest on disk
	*nextTake {
		var base = this.fileName;
		var n = 0;
		(this.dir +/+ base ++ "_*").pathMatch.do { |p|
			var rest = PathName(p).fileName.drop(base.size + 1);
			var end = rest.detectIndex { |c| c.isDecDigit.not } ? rest.size;
			if (end > 0) { n = max(n, rest.copyRange(0, end - 1).asInteger) };
		};
		^n + 1
	}

	// what a file is called after the take name: the sources in it
	*prSuffix { |file|
		var seen = [], parts;
		if (mode == \single) { ^"" };
		file.do { |c| if (seen.detect { |s| s === c[0] }.isNil) { seen = seen.add(c[0]) } };
		parts = seen.collect { |src|
			var chans = file.select { |c| c[0] === src }.collect(_.last);
			var str = src.label(label);
			// part of a wider source says which part, counting from 1
			if (chans.size < src.numChannels) {
				str = str ++ "_" ++ (if (chans.size == 1) {
					(chans.first + 1).asString
				} {
					"%-%".format(chans.first + 1, chans.last + 1)
				});
			};
			str
		};
		^parts.join("+").replace("/", "_").replace(" ", "_").replace(":", "_")
	}

	*prPathsFor { |plan|
		var take = switch(numbering,
			\take, { "_" ++ this.nextTake.asString.padLeft(3, "0") },
			\timestamp, { "_" ++ Date.localtime.stamp },
			\none, { "" }
		);
		var ext = "." ++ headerFormat;
		var paths = plan.collect { |file|
			var suffix = this.prSuffix(file);
			if (suffix.notEmpty) { suffix = "_" ++ suffix };
			this.dir +/+ this.fileName ++ take ++ suffix ++ ext
		};
		// two sources labelled alike must not write the same file
		paths = paths.collect { |p, i|
			var earlier = paths.copyRange(0, i - 1).count { |q| q == p };
			if (earlier > 0) { p.splitext.first ++ "_" ++ (earlier + 1) ++ ext } { p }
		};
		^paths
	}

	// contiguous runs of one source's channels: what one In.ar can read
	*prSegments { |file|
		var segs = [];
		file.do { |c|
			var last = segs.last;
			if (last.notNil and: { last[0] === c[0] } and: { (last[1] + last[2]) == c[1] }) {
				last[2] = last[2] + 1
			} {
				segs = segs.add([c[0], c[1], 1])
			};
		};
		^segs
	}

	// -------------------------------------------------------------- the take

	// Start writing. Every file gets its own disk synth and they are started
	// in one bundle, so the files line up to the sample. `dur` stops it for
	// you after that many seconds.
	*record { |dur|
		var plan, paths;
		if (recording) {
			"BusRecorder: already recording - stop first".warn;
			^this
		};
		if (this.server.serverRunning.not) {
			"BusRecorder: the server is not running - nothing to record".warn;
			^this
		};
		this.prEnsureSetup;
		plan = this.plan;
		if (plan.isEmpty) {
			"BusRecorder: nothing to record - add a source and arm it".warn;
			^this
		};
		paths = this.prPathsFor(plan);
		if (File.exists(this.dir).not) { File.mkdir(this.dir) };
		recording = true;
		paused = false;
		files = paths;
		pausedTotal = 0;
		startTime = nil;
		recPlan = plan.collect { |file, i|
			(segs: this.prSegments(file), path: paths[i], channels: file.size)
		};
		this.changed(\recording);
		fork {
			var todo = recPlan;
			todo.do { |rec|
				rec[\def] = this.prRecDef(rec[\segs].collect(_[2]));
				rec[\buffer] = Buffer.alloc(this.server, bufSize, rec[\channels]);
				rec[\buffer].write(rec[\path], headerFormat, sampleFormat, 0, 0, true);
			};
			todo.collect(_[\def]).do { |d, i|
				// each def once, however many files share its shape
				if (todo.copyRange(0, i - 1).detect { |r| r[\def] === d }.isNil) {
					this.prSendDef(d)
				};
			};
			this.server.sync;
			this.prMakeGroup;
			// stopped while the buffers were being opened
			if (recording.not or: { recPlan !== todo } or: { group.isNil }) {
				todo.do { |rec| rec[\buffer] !? { |b| b.close({ b.freeMsg }) } };
			} {
				this.server.makeBundle(nil, {
					todo.do { |rec|
						var args = [\buf, rec[\buffer]];
						rec[\segs].do { |seg, i|
							args = args.add(("in" ++ i).asSymbol).add(seg[0].index + seg[1]);
						};
						rec[\synth] = Synth.tail(group, rec[\def].name, args);
					};
					// pause was pressed before the take was ready
					if (paused) { todo.do { |rec| rec[\synth].run(false) } };
				});
				startTime = Main.elapsedTime;
				("BusRecorder: recording % file% in %".format(
					todo.size, if (todo.size == 1) { "" } { "s" }, this.dir)).postln;
				todo.do { |rec| ("  " ++ PathName(rec[\path]).fileName).postln };
				dur !? {
					// let go of itself before stopping: prFinish must not stop
					// the very routine it is running in
					stopTask = fork { dur.wait; stopTask = nil; this.stop };
				};
				this.changed(\recording);
			};
		};
		^this
	}

	*stop {
		if (recording.not) { ^this };
		this.prFinish("stopped");
		^this
	}

	// Hold the disk synths: nothing is written while they are paused, so the
	// file simply skips the gap.
	*pause {
		if (recording.not or: { paused }) { ^this };
		paused = true;
		pauseTime = Main.elapsedTime;
		if (this.server.serverRunning) {
			this.server.makeBundle(nil, {
				recPlan.do { |rec| rec[\synth] !? (_.run(false)) };
			});
		};
		this.changed(\recording);
		^this
	}

	*resume {
		if (recording.not or: { paused.not }) { ^this };
		if (startTime.notNil) { pausedTotal = pausedTotal + (Main.elapsedTime - pauseTime) };
		paused = false;
		if (this.server.serverRunning) {
			this.server.makeBundle(nil, {
				recPlan.do { |rec| rec[\synth] !? (_.run(true)) };
			});
		};
		this.changed(\recording);
		^this
	}

	*isRecording { ^recording }
	*isPaused { ^paused }

	// seconds written so far, or the length of the last take
	*duration {
		if (recording.not) { ^lastDuration };
		if (startTime.isNil) { ^0 };
		^(if (paused) { pauseTime } { Main.elapsedTime }) - startTime - pausedTotal
	}

	// Free the synths first, then close the files so they get their
	// headers. When the nodes are already gone - cmd-period took them - only
	// the closing is left to do.
	*prFinish { |why, nodesGone = false|
		var done = recPlan ? [];
		var dur = this.duration;
		if (stopTask.notNil and: { stopTask !== thisThread }) { stopTask.stop };
		stopTask = nil;
		recording = false;
		paused = false;
		lastDuration = dur;
		recPlan = nil;
		startTime = nil;
		if (this.server.serverRunning) {
			if (nodesGone.not) {
				this.server.makeBundle(nil, { done.do { |rec| rec[\synth] !? (_.free) } });
			};
			done.do { |rec| rec[\buffer] !? { |b| b.close({ b.freeMsg }) } };
		} {
			"BusRecorder: the server went away with the files open - they may be"
				" missing their headers".warn;
		};
		("BusRecorder: % after % s - % file%".format(why, dur.round(0.1),
			done.size, if (done.size == 1) { "" } { "s" })).postln;
		done.do { |rec| ("  " ++ rec[\path]).postln };
		this.changed(\recording);
		^this
	}

	// --------------------------------------------------------------- metering

	// One small synth per source, replying fifteen times a second. Off by
	// default and turned on while the window is open.
	*meterRate { ^15 }

	*meter_ { |flag = true|
		if (flag == metering) { ^this };
		metering = flag;
		if (flag) {
			this.prEnsureSetup;
			this.prMakeLevelResponder;
			this.prStartMeters;
		} {
			this.prStopMeters;
			levelFunc.free;
			levelFunc = nil;
			sources.do(_.prResetLevels);
		};
		this.changed(\meter);
		^this
	}

	*meter { ^metering }

	// -------------------------------------------------------------- monitoring

	// Route one source to the monitor output so you can hear what you are
	// about to record. One at a time: starting another stops the first.
	*listen { |name|
		var src = if (name.isKindOf(BusRecorderSource)) { name } { this.at(name) };
		if (src.isNil) {
			("BusRecorder: there is no source called %".format(name.asCompileString)).warn;
			^nil
		};
		if (src.isResolved.not) {
			("BusRecorder: % is not pointing at a bus right now".format(src.name)).warn;
			^nil
		};
		// the loop you would otherwise make by clicking listen on the main out
		if (src.overlaps(monitorOut, 2)) {
			("BusRecorder: % overlaps the monitor output (bus %) - listening to it"
				" would feed back, so it is refused".format(src.name, monitorOut)).warn;
			^nil
		};
		this.listenOff;
		monitoring = src;
		this.prStartMonitor;
		this.changed(\monitor);
		^src
	}

	*listenOff {
		monitorSynth !? { |syn|
			if (this.server.serverRunning) { syn.set(\gate, 0) };
		};
		monitorSynth = nil;
		monitoring = nil;
		this.changed(\monitor);
		^this
	}

	*isListeningTo { |name| ^monitoring.notNil and: { monitoring.name == name.asSymbol } }

	*monitorOut_ { |index|
		monitorOut = (index ? 0).asInteger.max(0);
		if (monitoring.notNil and: { monitoring.overlaps(monitorOut, 2) }) {
			("BusRecorder: the monitor output now overlaps % - listening stopped"
				.format(monitoring.name)).warn;
			this.listenOff;
		} {
			monitorSynth !? (_.set(\out, monitorOut));
		};
		this.changed(\monitor);
		^this
	}

	*monitorAmp_ { |amp|
		monitorAmp = (amp ? 0).clip(0, 2);
		monitorSynth !? (_.set(\amp, monitorAmp));
		this.changed(\monitor);
		^this
	}

	// ----------------------------------------------------------------- layouts

	// Next to the class files, so layouts travel with the repository however
	// it was installed - the same place BusManager keeps its own.
	*layoutDir { ^this.filenameSymbol.asString.dirname.dirname +/+ "layouts" }

	*ensureLayoutDir {
		var d = this.layoutDir;
		if (File.exists(d).not) { File.mkdir(d) };
		^d
	}

	*layoutPath { |name|
		var p = name.asString;
		if (p.contains("/").not and: { p.contains("\\").not }) { p = this.layoutDir +/+ p };
		p = p.standardizePath;
		if (PathName(p).extension.isEmpty and: { File.exists(p).not }) { p = p ++ ".scd" };
		^p
	}

	// what the window's dropdown offers: everything in the folder, newest first
	*layouts {
		^(this.layoutDir +/+ "*").pathMatch.select { |p|
			p.last.isPathSeparator.not and: { PathName(p).fileName.beginsWith(".").not }
		}.sort { |a, b| File.mtime(a) > File.mtime(b) }
	}

	// The sources and how the take is written, in one Event. A source
	// pointing at a Bus made elsewhere is written as the index it has now,
	// since a file cannot find that Bus again.
	*asDict {
		var frozen = sources.select { |s| s.kind == \bus };
		if (frozen.notEmpty) {
			("BusRecorder: % point% at a Bus made by something else and %"
				" saved as a bare index".format(frozen.collect(_.name).join(", "),
					if (frozen.size == 1) { "s" } { "" },
					if (frozen.size == 1) { "is" } { "are" })).postln;
		};
		^(
			version: 1,
			sources: sources.collect(_.asDict),
			settings: (
				mode: mode, label: label, numbering: numbering,
				dir: this.dir, fileName: fileName,
				headerFormat: headerFormat, sampleFormat: sampleFormat,
				monitorOut: monitorOut, monitorAmp: monitorAmp
			)
		)
	}

	*saveLayout { |name|
		var path;
		this.ensureLayoutDir;
		path = this.layoutPath(name ? "layout");
		File.use(path, "w", { |f| f.write(this.asDict.asCompileString) });
		("BusRecorder: saved layout to %".format(path)).postln;
		this.changed(\layouts);
		^path
	}

	// Replaces every source and every setting with what the file holds.
	*loadLayout { |name|
		var path = this.layoutPath(name ? "layout");
		var data, settings;
		if (File.exists(path).not) {
			("BusRecorder: no layout at %".format(path)).warn;
			^this
		};
		data = path.load;
		if (data.isNil or: { data[\sources].isNil }) {
			("BusRecorder: % is not a layout file".format(path)).warn;
			^this
		};
		this.prEnsureSetup;
		this.clear;
		data[\sources].do { |d|
			var src = if (d[\kind] == \name) {
				this.addName(d[\bus], d[\name], d[\channels])
			} {
				this.addIndex(d[\index] ? 0, d[\channels] ? 2, d[\name])
			};
			src !? { src.prSetArmed(d[\armed] ? true) };
		};
		settings = data[\settings] ? ();
		settings[\mode] !? { |v| this.mode_(v) };
		settings[\label] !? { |v| this.label_(v) };
		settings[\numbering] !? { |v| this.numbering_(v) };
		settings[\dir] !? { |v| this.dir_(v) };
		settings[\fileName] !? { |v| this.fileName_(v) };
		settings[\headerFormat] !? { |v| this.headerFormat_(v) };
		settings[\sampleFormat] !? { |v| this.sampleFormat_(v) };
		settings[\monitorOut] !? { |v| this.monitorOut_(v) };
		settings[\monitorAmp] !? { |v| this.monitorAmp_(v) };
		("BusRecorder: loaded layout %".format(path)).postln;
		this.changed(\sources);
		this.changed(\settings);
		^this
	}

	// --------------------------------------------------------------------- gui

	*gui { |x, y|
		if (guiObj.isNil) { guiObj = BusRecorderGUI(x, y) } { guiObj.front };
		^guiObj
	}

	*prGuiClosed { guiObj = nil }

	// ----------------------------------------------------- following BusManager

	// This class is a dependant of BusManager: whenever its buses change -
	// add, remove, rename, a reboot moving things - every source that
	// follows a name looks it up again.
	*update { |changer, what|
		if (what == \buses) { this.prResolveAll };
	}

	*prResolveAll {
		var moved = false;
		sources.do { |src|
			if (src.kind == \name and: { this.prResolve(src, false) }) {
				moved = true;
				this.prStartMeter(src);
				this.prRetarget(src);
				if (monitoring === src) {
					if (src.isResolved) { this.listen(src) } { this.listenOff };
				};
			};
		};
		if (moved) { this.changed(\sources) };
	}

	// answers whether the index changed; `loud` says whether to explain why
	// a source could not be found, which is wanted on add but not on every
	// change BusManager announces
	*prResolve { |src, loud = false|
		var moved = src.resolve;
		var entry;
		if (loud and: { src.isResolved.not }) {
			entry = bm !? { bm.at(src.busName) };
			case
			{ bm.isNil } {
				("BusRecorder: % follows the BusManager name %, but BusManager is not"
					" installed - it is left out until it is".format(src.name,
						src.busName.asCompileString)).warn;
			}
			{ entry.isNil } {
				("BusRecorder: BusManager has no bus called % - % is left out until"
					" it does".format(src.busName.asCompileString, src.name)).warn;
			}
			{ true } {
				("BusRecorder: % is a control bus - there is nothing to record, so"
					" % is left out".format(src.busName.asCompileString, src.name)).warn;
			};
		};
		^moved
	}

	// A source moved while a take is running: point the disk synths reading
	// it at the new index. One that went away cannot be silenced, so it says
	// so and keeps reading where it was.
	*prRetarget { |src|
		if (recording.not or: { recPlan.isNil }) { ^this };
		recPlan.do { |rec|
			rec[\segs].do { |seg, i|
				if (seg[0] === src and: { rec[\synth].notNil }) {
					if (src.isResolved) {
						rec[\synth].set(("in" ++ i).asSymbol, src.index + seg[1]);
					} {
						("BusRecorder: % lost its bus while recording - % keeps"
							" reading the old index until you stop".format(src.name,
								PathName(rec[\path]).fileName)).warn;
					};
				};
			};
		};
	}

	// ----------------------------------------------------------------- private

	*prEnsureSetup {
		if (hooksAdded) { ^this };
		hooksAdded = true;
		bm = \BusManager.asClass;
		bm !? { bm.addDependant(this) };
		bootFunc = { this.prOnBoot };
		treeFunc = { this.prOnTree };			// also runs after cmd-period
		quitFunc = { this.prOnQuit };
		ServerBoot.add(bootFunc, this.server);
		ServerTree.add(treeFunc, this.server);
		ServerQuit.add(quitFunc, this.server);
		^this
	}

	*prRemoveHooks {
		if (hooksAdded.not) { ^this };
		bm !? { bm.removeDependant(this) };
		ServerBoot.remove(bootFunc, this.server);
		ServerTree.remove(treeFunc, this.server);
		ServerQuit.remove(quitFunc, this.server);
		hooksAdded = false;
		^this
	}

	*prMake { |name, kind|
		idCounter = idCounter + 1;
		^BusRecorderSource(name, kind, idCounter)
	}

	*prPut { |src|
		var old = this.at(src.name);
		if (old.notNil) {
			this.prDrop(old);
			sources = sources.reject { |s| s === old };
			// arming is a decision about the name, so it survives replacement
			src.prSetArmed(old.armed);
		};
		sources = sources.add(src);
		if (metering) { this.prStartMeter(src) };
		this.changed(\sources);
		^src
	}

	// Stop everything running on a source. It stays in `sources` - the
	// callers take it out. Nothing is ever freed: the recorder only reads.
	*prDrop { |src|
		if (monitoring === src) { this.listenOff };
		this.prStopMeter(src);
		^src
	}

	// ------------------------------------------------------------ meter nodes

	*prMakeLevelResponder {
		levelFunc.free;
		// [path, nodeID, replyID, id, level per channel...]
		levelFunc = OSCFunc({ |msg|
			var src = sources.detect { |s| s.id == msg[3] };
			src !? { src.prSetLevels(msg.copyToEnd(4)) };
		}, '/brLevel', this.server.addr);
	}

	// The defs have to be on the server before a synth asks for one, so
	// both of these do the same steps in the same order: build, send, sync.
	*prStartMeters {
		if (this.server.serverRunning.not) { ^this };
		fork {
			sources.do { |s| if (s.isResolved) { this.prMeterDef(s.numChannels) } };
			this.prSendDefs;
			this.server.sync;
			this.prMakeGroup;
			sources.do { |s| this.prMakeMeterSynth(s) };
		};
		^this
	}

	*prStopMeters {
		sources.do { |s| this.prStopMeter(s) };
		^this
	}

	// one source, metered on its own - added or moved while the window is open
	*prStartMeter { |src|
		var def;
		this.prStopMeter(src);
		if (metering.not or: { src.isResolved.not } or: { this.server.serverRunning.not }) {
			^this
		};
		def = this.prMeterDef(src.numChannels);
		fork {
			this.prSendDef(def);
			this.server.sync;
			this.prMakeGroup;
			this.prMakeMeterSynth(src);
		};
		^this
	}

	*prMakeMeterSynth { |src|
		// metering may have gone off, or the source away, while the def was
		// on its way to the server
		if (metering.not or: { group.isNil } or: { sources.includes(src).not }
			or: { src.isResolved.not }) { ^this };
		// two of these in flight after cmd-period: the second takes over
		this.prStopMeter(src);
		src.prSetMeterSynth(
			Synth(this.prMeterDef(src.numChannels).name,
				[\in, src.index, \id, src.id], group));
		^this
	}

	*prStopMeter { |src|
		src.meterSynth !? { |syn|
			if (this.server.serverRunning) { syn.free };
		};
		src.prSetMeterSynth(nil);
		src.prResetLevels;
		^this
	}

	*prMakeGroup {
		if (this.server.serverRunning.not) { ^nil };
		// after the default group, so the disk synths and the meters read a
		// bus once everything that writes to it has run this block
		group ?? { group = Group.after(this.server.defaultGroup) };
		^group
	}

	// ---------------------------------------------------------- monitor node

	*prStartMonitor {
		var src = monitoring;
		var def;
		if (src.isNil or: { this.server.serverRunning.not }) { ^this };
		def = this.prMonitorDef(src.numChannels);
		fork {
			this.prSendDef(def);
			this.server.sync;
			this.prMakeGroup;
			// only if listening was not turned off while we waited
			if (monitoring === src and: { group.notNil }) {
				monitorSynth = Synth(def.name, [
					\in, src.index, \out, monitorOut, \amp, monitorAmp
				], group, \addToTail);
			};
		};
		^this
	}

	// ------------------------------------------------------------- synthdefs

	// One disk def per shape of file - the channel count of each run it
	// reads - built the first time that shape is wanted and kept so it can
	// be sent again after a reboot.
	*prRecDef { |counts|
		var key = ("br_rec_" ++ counts.join("_")).asSymbol;
		^defs[key] ?? {
			defs[key] = SynthDef(key, {
				var sig = counts.collect { |n, i|
					In.ar(("in" ++ i).asSymbol.kr(0), n)
				}.flatten;
				DiskOut.ar(\buf.kr(0), sig);
			});
			defs[key]
		}
	}

	*prMeterDef { |channels|
		var key = ("br_meter_" ++ channels).asSymbol;
		var replyRate = this.meterRate;
		^defs[key] ?? {
			// InFeedback, not In: correct wherever the synth sits relative to
			// whatever writes the bus, one block late, which nobody can see
			defs[key] = SynthDef(key, {
				var sig = InFeedback.ar(\in.kr(0), channels);
				SendReply.kr(Impulse.kr(replyRate), '/brLevel',
					[\id.kr(0)] ++ Amplitude.kr(sig, 0.01, 0.3).asArray);
			});
			defs[key]
		}
	}

	*prMonitorDef { |channels|
		var key = ("br_monitor_" ++ channels).asSymbol;
		^defs[key] ?? {
			defs[key] = SynthDef(key, {
				var sig = InFeedback.ar(\in.kr(0), channels);
				var env = EnvGen.kr(Env.asr(0.02, 1, 0.05), \gate.kr(1), doneAction: 2);
				sig = case
					{ channels == 1 } { sig.dup }
					{ channels == 2 } { sig }
					{ true } { Splay.ar(sig) };			// wider sources folded to stereo
				sig = sig * \amp.kr(0.5).lag(0.05) * env;
				// listening is for checking, not for mixing: it never clips the desk
				Out.ar(\out.kr(0), Limiter.ar(sig, 0.99, 0.005));
			});
			defs[key]
		}
	}

	// SynthDef:add reaches the default server; any other one has to be sent
	// to as well
	*prSendDef { |def|
		def.add;
		if (this.server != Server.default) { def.send(this.server) };
		^def
	}

	// a reboot leaves the server with no defs at all
	*prSendDefs { defs.do { |d| this.prSendDef(d) } }

	// ------------------------------------------------------- server lifecycle

	*prOnBoot {
		this.prSendDefs;
		// BusManager re-takes its buses on the same hook; whichever order the
		// hooks ran in, looking again now is right, and its next \buses
		// announcement makes it right again
		this.prResolveAll;
		this.changed(\sources);
	}

	// the tree was rebuilt - after a boot, or after cmd-period took every
	// node, disk synths included: the files have to be closed
	*prOnTree {
		var listening = monitoring !? (_.name);
		if (recording) {
			this.prFinish("cut short - every node was freed", true);
		};
		group = nil;
		monitorSynth = nil;
		monitoring = nil;
		sources.do { |s| s.prSetMeterSynth(nil) };
		if (metering) { this.prStartMeters };
		listening !? { |n| this.listen(n) };
	}

	*prOnQuit {
		if (recording) { this.prFinish("cut short - the server quit", true) };
		group = nil;
		monitorSynth = nil;
		monitoring = nil;
		sources.do { |s| s.prSetMeterSynth(nil); s.prResetLevels };
		this.changed(\monitor);
	}
}
