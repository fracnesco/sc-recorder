// =====================================================================
// BusRecorderGUI - the window: add, arm, meter, listen, record
//
//   recorder   [record] [pause] 00:12.3      monitor out [0] level --- [x] meters  save layout  load layout
//   file [take    ] in [Recordings] [open]  as [per source]  [by name]  [take number]  [wav] [int24]
//   name       bus        ch  index  level              arm  listen  x
//   main       index       2      0  ||||......         [x]  listen  x
//   drums      drums       2     16  ||........         [x]  listen  x
//   add [ name ] bus [drums v] index [ 16 ] ch [2] [add]
//
// One row per source. The name cell is editable and renames it, the
// meter shows one bar per channel, arm decides whether it goes into the
// next take, and listen routes it to the monitor output until you click
// it again. The file row is how the take is written: base name, folder,
// how it is split into files, what the files are labelled with, how
// takes are numbered, and the format.
//
// Metering follows the window: on when it opens, off when it closes.
// Listening and recording do not - they are routing and writing, and
// they carry on until you stop them.
// =====================================================================

BusRecorderGUI {
	classvar <colBg, <colPanel, <colText, <colDim, <colAccent;
	classvar <colMeter, <colClip, <colOff, <colRec;

	var <window, scroller, rowsView, rows;
	var ctrl, bmCtrl, updater, meterWasOn, lastStatus, nextSummary;
	var recBtn, pauseBtn, timeText, outBox, ampSlider, meterBox, statusText;
	var fileField, dirBtn, modeMenu, labelMenu, numberMenu, headerMenu, formatMenu;
	var nameField, busMenu, indexBox, chanBox, layoutMenu, layoutPaths, busNames;
	var font, smallFont, boldFont, monoFont;

	*initClass {
		colBg     = Color(0.13, 0.13, 0.15);
		colPanel  = Color(0.20, 0.20, 0.24);
		colText   = Color(0.86, 0.87, 0.89);
		colDim    = Color(0.55, 0.56, 0.62);
		colAccent = Color(0.98, 0.65, 0.20);
		colMeter  = Color(0.42, 0.76, 0.95);
		colClip   = Color(0.95, 0.35, 0.30);
		colOff    = Color(0.09, 0.10, 0.12);
		colRec    = Color(0.90, 0.25, 0.25);
	}

	*new { |x, y| ^super.new.init(x, y) }

	init { |x, y|
		var bm = \BusManager.asClass;
		rows = [];
		busNames = [];
		font = Font(Font.defaultSansFace, 11);
		smallFont = Font(Font.defaultSansFace, 9);
		boldFont = Font(Font.defaultSansFace, 12, true);
		monoFont = Font(Font.defaultMonoFace, 12);
		this.prBuild(x, y);
		ctrl = SimpleController(BusRecorder)
			.put(\sources, { { this.prRebuildRows; this.prRefreshNext }.defer })
			.put(\monitor, { { this.prRefreshRows; this.prRefreshHeader }.defer })
			.put(\meter, { { this.prRefreshHeader }.defer })
			.put(\settings, { { this.prRefreshFiles; this.prRefreshNext }.defer })
			.put(\recording, { { this.prRefreshHeader; this.prRefreshNext }.defer })
			.put(\layouts, { { this.prRefreshLayouts }.defer });
		// the add row's dropdown lists BusManager's names, when there is one
		bm !? {
			bmCtrl = SimpleController(bm)
				.put(\buses, { { this.prRefreshBusMenu }.defer });
		};
		meterWasOn = BusRecorder.metering;
		BusRecorder.meter_(true);
		this.prRebuildRows;
		this.prRefreshHeader;
		this.prRefreshFiles;
		this.prRefreshBusMenu;
		this.prRefreshLayouts;
		this.prRefreshNext;
		this.prStartUpdater;
		window.front;
		^this
	}

	front { window !? { if (window.isClosed.not) { window.front } } }

	close { window !? { if (window.isClosed.not) { window.close } } }

	// --------------------------------------------------------------- build

	prBuild { |x, y|
		window = Window("recorder", Rect(x ? 120, y ? 120, 860, 460));
		if (GUI.id == \qt) { window.view.palette = QPalette.dark };
		window.view.background = colBg;

		rowsView = View().layout_(VLayout().spacing_(3).margins_(0));
		scroller = ScrollView().hasBorder_(false).background_(colBg);
		scroller.canvas = rowsView;

		window.layout = VLayout(
			this.prHeaderRow,
			this.prFilesRow,
			this.prColumnRow,
			[scroller, stretch: 1],
			this.prAddRow,
			this.prStatusRow
		).margins_(8).spacing_(6);

		window.onClose = {
			ctrl.remove;
			bmCtrl !? (_.remove);
			updater !? (_.stop);
			// leave metering as we found it, and leave listening and recording alone
			if (meterWasOn.not) { BusRecorder.meter_(false) };
			BusRecorder.prGuiClosed;
		};
	}

	prHeaderRow {
		recBtn = Button().fixedWidth_(64)
			.states_([["record", colText, colPanel], ["stop", Color.white, colRec]])
			.action_({ |v|
				if (BusRecorder.recording) { BusRecorder.stop } { BusRecorder.record };
			});
		pauseBtn = Button().fixedWidth_(56)
			.states_([["pause", colText, colPanel], ["resume", Color.black, colAccent]])
			.action_({ |v|
				if (BusRecorder.paused) { BusRecorder.resume } { BusRecorder.pause };
			});
		timeText = StaticText().font_(monoFont).stringColor_(colText).fixedWidth_(64)
			.string_("00:00.0");
		outBox = NumberBox().fixedWidth_(46).decimals_(0).clipLo_(0).clipHi_(1023)
			.action_({ |v| BusRecorder.monitorOut_(v.value.asInteger) });
		ampSlider = Slider().orientation_(\horizontal).fixedWidth_(80).fixedHeight_(18)
			.action_({ |v| BusRecorder.monitorAmp_(v.value * 2) });
		meterBox = CheckBox().string_("meters")
			.action_({ |v| BusRecorder.meter_(v.value) });
		layoutMenu = PopUpMenu().fixedWidth_(112)
			.action_({ |v|
				var i = v.value ? 0;
				var path = if (i > 0) { layoutPaths[i - 1] } { nil };
				v.value = 0;
				path !? { BusRecorder.loadLayout(path) };
			});

		^HLayout(
			StaticText().string_("recorder").font_(boldFont)
				.stringColor_(colAccent).fixedWidth_(64),
			recBtn, pauseBtn, timeText,
			nil,
			this.prLabel("monitor out", 68), outBox,
			this.prLabel("level", 30), ampSlider,
			meterBox,
			this.prButton("save layout", 76, {
				Dialog.savePanel({ |p| BusRecorder.saveLayout(p); this.prRefreshLayouts },
					path: BusRecorder.ensureLayoutDir);
			}),
			layoutMenu
		).spacing_(4)
	}

	// how the take is written
	prFilesRow {
		fileField = TextField().fixedWidth_(110).font_(font)
			.action_({ |v| BusRecorder.fileName_(v.string) });
		dirBtn = Button().fixedWidth_(130)
			.action_({
				FileDialog({ |p| BusRecorder.dir_(p) }, fileMode: 2, acceptMode: 0,
					stripResult: true, path: BusRecorder.dir);
			});
		modeMenu = PopUpMenu().fixedWidth_(104)
			.items_(["one file", "stereo pairs", "one per source", "one per channel"])
			.action_({ |v| BusRecorder.mode_(BusRecorder.modes[v.value]) });
		labelMenu = PopUpMenu().fixedWidth_(76)
			.items_(["by name", "by index"])
			.action_({ |v| BusRecorder.label_(BusRecorder.labels[v.value]) });
		numberMenu = PopUpMenu().fixedWidth_(92)
			.items_(["take number", "timestamp", "no number"])
			.action_({ |v| BusRecorder.numbering_(BusRecorder.numberings[v.value]) });
		headerMenu = PopUpMenu().fixedWidth_(54).items_(BusRecorder.headerFormats)
			.action_({ |v| BusRecorder.headerFormat_(BusRecorder.headerFormats[v.value]) });
		formatMenu = PopUpMenu().fixedWidth_(58).items_(BusRecorder.sampleFormats)
			.action_({ |v| BusRecorder.sampleFormat_(BusRecorder.sampleFormats[v.value]) });

		^HLayout(
			this.prLabel("file", 26), fileField,
			this.prLabel("in", 14), dirBtn,
			this.prButton("open", 40, { BusRecorder.dir.openOS }),
			this.prLabel("as", 14), modeMenu,
			labelMenu, numberMenu, headerMenu, formatMenu,
			nil
		).spacing_(4)
	}

	prColumnRow {
		^HLayout(
			this.prLabel("name", 110),
			this.prLabel("bus", 84),
			this.prLabel("ch", 26),
			this.prLabel("index", 44),
			this.prLabel("level", 60),
			nil,
			this.prLabel("arm", 34),
			this.prLabel("", 52),
			this.prLabel("", 24)
		).spacing_(4)
	}

	prAddRow {
		nameField = TextField().fixedWidth_(110).font_(font)
			.action_({ this.prAddFromFields });
		busMenu = PopUpMenu().fixedWidth_(110)
			.action_({ |v| this.prBusMenuChanged });
		indexBox = NumberBox().fixedWidth_(46).decimals_(0).clipLo_(0).clipHi_(4095)
			.value_(0);
		chanBox = NumberBox().fixedWidth_(40).decimals_(0).clipLo_(1).clipHi_(128)
			.value_(2);

		^HLayout(
			this.prLabel("add", 26),
			nameField,
			this.prLabel("bus", 20), busMenu,
			this.prLabel("index", 30), indexBox,
			this.prLabel("ch", 20), chanBox,
			this.prButton("add", 46, { this.prAddFromFields }),
			nil
		).spacing_(4)
	}

	prStatusRow {
		statusText = StaticText().font_(smallFont).stringColor_(colDim);
		^HLayout(statusText).spacing_(4)
	}

	// the dropdown offers "index", then every BusManager name
	prRefreshBusMenu {
		var bm = \BusManager.asClass;
		// items is nil until first set, and item would index into it
		var was = (busMenu.items ? [])[busMenu.value ? 0];
		busNames = bm !? { bm.entries.select(_.isAudio).collect(_.name) } ? [];
		busMenu.items = ["index"] ++ busNames.collect(_.asString);
		busMenu.value = (busMenu.items.indexOfEqual(was) ? 0);
		busMenu.toolTip = if (bm.isNil) {
			"install BusManager to pick a bus by name"
		} {
			"a bus from BusManager, followed by name"
		};
		this.prBusMenuChanged;
	}

	// picking a name fills in its index and width; the index box is only
	// yours to type in when "index" is chosen
	prBusMenuChanged {
		var bm = \BusManager.asClass;
		var i = busMenu.value ? 0;
		var entry;
		indexBox.enabled = (i == 0);
		if (i > 0 and: { bm.notNil }) {
			entry = bm.at(busNames[i - 1]);
			entry !? {
				indexBox.value = entry.index;
				chanBox.value = entry.numChannels;
			};
		};
	}

	prAddFromFields {
		var name = nameField.string.stripWhiteSpace;
		var i = busMenu.value ? 0;
		var chans = chanBox.value.asInteger;
		var bm = \BusManager.asClass;
		var busName, entry;
		if (i == 0) {
			BusRecorder.addIndex(indexBox.value.asInteger, chans,
				if (name.isEmpty) { nil } { name.asSymbol });
		} {
			busName = busNames[i - 1];
			entry = bm !? { bm.at(busName) };
			// the whole bus unless fewer channels were asked for
			BusRecorder.addName(busName,
				if (name.isEmpty) { nil } { name.asSymbol },
				if (entry.notNil and: { chans < entry.numChannels }) { chans } { nil });
		};
		nameField.string = "";
	}

	// ---------------------------------------------------------------- rows

	prRebuildRows {
		var items;
		rows = [];
		rowsView.children.copy.do(_.remove);
		items = BusRecorder.sources.collect { |src| this.prMakeRow(src) };
		if (items.isEmpty) {
			items = [
				StaticText().string_("no sources yet - add one below")
					.font_(font).stringColor_(colDim).fixedHeight_(22)
			];
		};
		rowsView.layout = VLayout(*(items ++ [nil])).spacing_(3).margins_(0);
		this.prRefreshRows;
	}

	prMakeRow { |src|
		var row = View().fixedHeight_(22);
		var nameBox, meterView, armBox, listenBtn, indexCell;

		nameBox = TextField().fixedWidth_(110).font_(font)
			.string_(src.name.asString)
			.action_({ |v|
				var wanted = v.string.stripWhiteSpace;
				if (wanted.isEmpty or: { wanted == src.name.asString }) {
					v.string = src.name.asString;
				} {
					BusRecorder.rename(src.name, wanted.asSymbol);
				};
			});

		meterView = UserView().fixedHeight_(16);
		meterView.drawFunc = { |uv| this.prDrawMeter(uv, src) };

		armBox = CheckBox().fixedWidth_(34).value_(src.armed)
			.action_({ |v| BusRecorder.arm(src.name, v.value) });

		listenBtn = Button().fixedWidth_(52)
			.action_({
				if (BusRecorder.isListeningTo(src.name)) {
					BusRecorder.listenOff
				} {
					BusRecorder.listen(src.name)
				};
			});

		// a source nothing points at says so, in the colour of trouble
		indexCell = this.prCell(if (src.isResolved) { src.index.asString } { "?" }, 44);
		if (src.isResolved.not) {
			indexCell.stringColor = colClip;
			indexCell.toolTip = "not pointing at a bus - left out of the take";
		};

		row.layout = HLayout(
			nameBox,
			this.prCell(src.origin, 84),
			this.prCell(src.numChannels.asString, 26),
			indexCell,
			[meterView, stretch: 1],
			armBox,
			listenBtn,
			this.prButton("x", 24, { BusRecorder.remove(src.name) })
		).margins_(0).spacing_(4);

		rows = rows.add((src: src, meter: meterView, listen: listenBtn, arm: armBox));
		^row
	}

	prRefreshRows {
		rows.do { |r|
			var src = r[\src];
			var on = BusRecorder.isListeningTo(src.name);
			r[\arm].value = src.armed;
			if (src.isResolved) {
				r[\listen].states_([[
					"listen",
					if (on) { Color.black } { colText },
					if (on) { colAccent } { colPanel }
				]]);
				r[\listen].enabled = true;
			} {
				r[\listen].states_([["-", colDim, colPanel]]);
				r[\listen].enabled = false;
			};
		};
	}

	// one bar per channel, so you can see which side is sounding
	prDrawMeter { |uv, src|
		var w = uv.bounds.width, h = uv.bounds.height;
		var levels = src.levels;
		var n = levels.size.max(1);
		var gap = if (n > 1) { 1 } { 0 };
		var barH = ((h - ((n - 1) * gap)) / n).max(2);

		levels.do { |lv, i|
			var amp = (lv ? 0).abs.clip(0, 1.5);
			// square root, so the quiet end of the scale is where the eye is
			var scaled = amp.sqrt.clip(0, 1);
			var top = i * (barH + gap);
			Pen.fillColor = colOff;
			Pen.fillRect(Rect(0, top, w, barH));
			Pen.fillColor = if (amp > 0.98) { colClip } { colMeter };
			Pen.fillRect(Rect(0, top, w * scaled, barH));
		};
	}

	// ------------------------------------------------------------- refresh

	prRefreshHeader {
		recBtn.value = BusRecorder.recording.binaryValue;
		pauseBtn.value = BusRecorder.paused.binaryValue;
		pauseBtn.enabled = BusRecorder.recording;
		outBox.value = BusRecorder.monitorOut;
		ampSlider.value = BusRecorder.monitorAmp / 2;
		meterBox.value = BusRecorder.metering;
	}

	prRefreshFiles {
		var d = BusRecorder.dir;
		fileField.string = BusRecorder.fileName;
		dirBtn.states = [[PathName(d).fileName, colText, colPanel]];
		dirBtn.toolTip = d;
		modeMenu.value = BusRecorder.modes.indexOf(BusRecorder.mode) ? 0;
		labelMenu.value = BusRecorder.labels.indexOf(BusRecorder.label) ? 0;
		numberMenu.value = BusRecorder.numberings.indexOf(BusRecorder.numbering) ? 0;
		headerMenu.value = BusRecorder.headerFormats.indexOfEqual(BusRecorder.headerFormat) ? 0;
		formatMenu.value = BusRecorder.sampleFormats.indexOfEqual(BusRecorder.sampleFormat) ? 0;
	}

	// What the next take would write. This reads the folder to find the
	// take number, so it is done when something changes, not fifteen times
	// a second with the meters.
	prRefreshNext {
		var paths = BusRecorder.nextPaths;
		var names = paths.collect { |p| PathName(p).fileName };
		nextSummary = if (paths.isEmpty) {
			"nothing armed - the next take would write no files"
		} {
			"next take: % file% - %".format(paths.size,
				if (paths.size == 1) { "" } { "s" }, names.join(", "))
		};
		statusText.toolTip = if (paths.isEmpty) { nil } { paths.join("\n") };
		lastStatus = nil;
	}

	prRefreshLayouts {
		layoutPaths = BusRecorder.layouts;
		layoutMenu.items = ["load layout"] ++ layoutPaths.collect { |p|
			PathName(p).fileNameWithoutExtension
		};
		layoutMenu.value = 0;
		layoutMenu.toolTip = if (layoutPaths.isEmpty) {
			"no layouts yet - save one and it appears here"
		} {
			"newest first:\n" ++ layoutPaths.join("\n")
		};
	}

	prRefreshStatus {
		var status;
		timeText.string = this.prTime(BusRecorder.duration);
		if (BusRecorder.recording) {
			status = "% · % file% · %".format(
				if (BusRecorder.paused) { "paused" } { "recording" },
				BusRecorder.files.size, if (BusRecorder.files.size == 1) { "" } { "s" },
				BusRecorder.dir);
		} {
			status = "% source% · % · %".format(
				BusRecorder.size,
				if (BusRecorder.size == 1) { "" } { "s" },
				if (BusRecorder.server.serverRunning) {
					if (BusRecorder.metering) { "metering" } { "meters off" }
				} {
					"server not running"
				},
				nextSummary ? "");
		};
		BusRecorder.monitoring !? { |src| status = status + "· listening to" + src.name };
		if (status != lastStatus) {
			lastStatus = status;
			statusText.string = status;
		};
	}

	prTime { |secs|
		var m = (secs / 60).floor.asInteger;
		var s = (secs - (m * 60)).round(0.1);
		^"%:%".format(m.asString.padLeft(2, "0"), s.asString.padLeft(4, "0"))
	}

	prStartUpdater {
		updater = Routine({
			var wait = 1 / BusRecorder.meterRate;
			loop {
				wait.wait;
				if (window.isClosed) {
					updater.stop;
				} {
					if (BusRecorder.metering) { rows.do { |r| r[\meter].refresh } };
					this.prRefreshStatus;
				};
			};
		}).play(AppClock);
	}

	// -------------------------------------------------------------- widgets

	prLabel { |str, width|
		^StaticText().string_(str).font_(smallFont).stringColor_(colDim)
			.fixedWidth_(width)
	}

	prCell { |str, width|
		^StaticText().string_(str).font_(font).stringColor_(colText)
			.fixedWidth_(width)
	}

	prButton { |str, width, func|
		^Button().fixedWidth_(width).states_([[str, colText, colPanel]])
			.action_({ func.value })
	}
}
