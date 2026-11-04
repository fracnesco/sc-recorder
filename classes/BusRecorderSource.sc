// =====================================================================
// BusRecorderSource - one thing the recorder is pointed at
//
// A name, a way of finding a bus, and whether it goes into the next
// take. The way of finding the bus is what `kind` is about:
//
//   \index     a bare index and a channel count: BusRecorder.addIndex(16, 2).
//              Nothing is allocated - the recorder only ever reads.
//   \name      a name in BusManager: BusRecorder.add(\drums, \reverb).
//              Resolved through \BusManager.asClass, never a hard
//              reference, so the source follows the name when the bus
//              behind it moves and becomes unresolved - index nil - if
//              the name goes away. An unresolved source is skipped.
//   \bus       a Bus something else made. Read at whatever index it has,
//              and written to a layout as that index.
//
// `channels` is how many channels are recorded, counted from the start
// of the bus. Nil means all of them, which is what you want unless you
// are taking the left half of a stereo pair.
//
// `levels` holds one amplitude per channel, written fifteen times a
// second by the meter responder in BusRecorder while metering is on.
// =====================================================================

BusRecorderSource {
	var <name, <kind, <id;
	var <busName;				// \name sources: the BusManager name followed
	var <bus;					// the Bus being read; nil while a name is unknown
	var <channels;				// requested channel count; nil = the whole bus
	var <armed = true;
	var <levels;
	var <meterSynth;

	*new { |name, kind, id| ^super.new.init(name, kind, id) }

	init { |argName, argKind, argId|
		name = argName.asSymbol;
		kind = argKind ? \index;
		id = argId;
		levels = [];
		^this
	}

	// --------------------------------------------------------------- the bus

	index { ^bus !? (_.index) }

	// how many channels the recorder reads: the bus's, or fewer if asked
	numChannels {
		if (bus.isNil) { ^channels ? 0 };
		^if (channels.isNil) { bus.numChannels } { channels.min(bus.numChannels) }
	}

	isResolved { ^bus.notNil and: { bus.index.notNil } }

	// armed, and pointing somewhere: what the next take will include
	isRecordable { ^armed and: { this.isResolved } }

	// Look the name up again. Only a \name source can change its mind; the
	// other kinds answer false without looking. Answers whether the index
	// is different from before, so the caller knows to retarget the meter.
	resolve {
		var was = this.index;
		var bm, entry;
		if (kind != \name) { ^false };
		bm = \BusManager.asClass;
		entry = bm !? { bm.at(busName) };
		bus = if (entry.notNil and: { entry.isAudio }) { entry.bus } { nil };
		this.prResetLevels;
		^this.index != was
	}

	// what the window shows in its "bus" column: where the source comes from
	origin {
		^switch(kind,
			\name, { busName.asString },
			\index, { "index" },
			\bus, { "a Bus" }
		)
	}

	// The word that goes into a file name: the source's name, or the index
	// of the bus - the two things a take can be told apart by.
	label { |mode = \name|
		^if (mode == \index and: { this.isResolved }) {
			"bus" ++ this.index
		} {
			name.asString
		}
	}

	// Does this source overlap `otherChannels` channels starting at
	// `otherIndex`? Two ranges that share one channel are the same signal
	// path as far as feedback is concerned, which is what the monitor guard
	// needs to know.
	overlaps { |otherIndex, otherChannels = 1|
		if (this.isResolved.not) { ^false };
		^(this.index < (otherIndex + otherChannels))
			and: { otherIndex < (this.index + this.numChannels) }
	}

	// ------------------------------------------------------------ metering

	// the loudest channel - what a "is anything in there" test wants
	level { ^levels.maxItem ? 0 }

	isSilent { |floor = 0.001| ^this.level < floor }

	prSetLevels { |array| levels = array }
	prResetLevels { levels = 0 ! this.numChannels }
	prSetMeterSynth { |synth| meterSynth = synth }

	prSetBus { |newBus| bus = newBus; this.prResetLevels }
	prSetBusName { |newName| busName = newName.asSymbol }
	prSetChannels { |n| channels = n; this.prResetLevels }
	prSetName { |newName| name = newName.asSymbol }
	prSetArmed { |flag| armed = flag == true }

	// ------------------------------------------------------------- layouts

	// What a layout file holds. A \bus source belongs to whatever made the
	// Bus and cannot be found again by a file, so it is written down as the
	// index it had - BusRecorder says so when it saves.
	asDict {
		var dict = (name: name, kind: kind, armed: armed);
		switch(kind,
			\name, {
				dict.put(\bus, busName);
				channels !? { dict.put(\channels, channels) };
			},
			{
				dict.put(\kind, \index).put(\index, this.index ? 0)
					.put(\channels, this.numChannels.max(1));
			}
		);
		^dict
	}

	printOn { |stream|
		stream << "BusRecorderSource(" << name.asCompileString << ", "
			<< (this.index ? "?") << ", " << this.numChannels << "ch, "
			<< kind << (if (armed) { "" } { ", off" }) << ")"
	}
}
