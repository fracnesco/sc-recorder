// =====================================================================
// BusRecorder demo - evaluate one block at a time (ctrl/cmd + enter)
// =====================================================================

// 1. boot, and open the window ----------------------------------------
s.boot;

BusRecorder.gui;

// Nothing in it yet. Metering is on while the window is open, so a source
// starts moving the moment it is added, if anything is playing in its bus.


// 2. sources ----------------------------------------------------------
// from the window: the row at the bottom. Or from code:

BusRecorder.add(\main, 0, 2);        // bus 0, two channels: the main out
BusRecorder.addIndex(2, 2);          // a bare index, called bus2

// with BusManager installed, a name works too, and is followed from then on:
// BusManager.add(\drums, 2);
// BusRecorder.add(\drums);           // the source is called drums as well
// BusRecorder.add(\kick, \drums, 1); // just the left channel, called kick

BusRecorder.names;
BusRecorder.at(\main);


// 3. something to record ----------------------------------------------
(
~noise = { Out.ar(0, PinkNoise.ar(0.1) ! 2) }.play;
~tone = { Out.ar(2, SinOsc.ar([220, 330], 0, 0.1)) }.play;
)

// The main row is moving. bus2 is a private bus, so click listen on it to hear
// what is going in - and click listen again to stop.

BusRecorder.listen(\bus2);
BusRecorder.listenOff;


// 4. how the take is written ------------------------------------------
BusRecorder.fileName = "demo";
BusRecorder.dir;                     // SuperCollider's own recordings folder
// BusRecorder.dir = "~/Music/piece/takes";

BusRecorder.mode = \single;          // one file, four channels
BusRecorder.nextPaths;               // -> [ .../demo_001.wav ]

BusRecorder.mode = \sources;         // one file per source
BusRecorder.nextPaths;               // -> demo_001_main.wav, demo_001_bus2.wav

BusRecorder.mode = \pairs;           // stereo files, cut two channels at a time
BusRecorder.mode = \mono;            // one file per channel: demo_001_main_1.wav ...

BusRecorder.label = \index;          // files labelled bus0, bus2 instead of names
BusRecorder.nextPaths;
BusRecorder.label = \name;

BusRecorder.numbering = \timestamp;  // demo_260824_213000_main.wav
BusRecorder.numbering = \take;       // demo_001_main.wav, and the next is 002


// 5. a take -----------------------------------------------------------
BusRecorder.mode = \sources;
BusRecorder.record;                  // every file starts on the same sample
BusRecorder.duration;                // seconds so far
BusRecorder.pause;                   // nothing is written while paused
BusRecorder.resume;
BusRecorder.stop;                    // the post window lists what was written
BusRecorder.files;

BusRecorder.record(5);               // or give it a length, and it stops itself


// 6. leaving one out --------------------------------------------------
// arm decides what goes into the next take; a disarmed source stays in the
// list, metered and listenable, just not written

BusRecorder.disarm(\bus2);
BusRecorder.nextPaths;               // just main now
BusRecorder.arm(\bus2);


// 7. layouts ----------------------------------------------------------
// the sources and every setting - name, folder, mode, format - in one file

BusRecorder.saveLayout("demo");
BusRecorder.layouts;

BusRecorder.clear;                   // everything gone
BusRecorder.loadLayout("demo");      // and back


// 8. tidying up -------------------------------------------------------
~noise.free; ~tone.free;
BusRecorder.clear;
