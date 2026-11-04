# BusRecorder

**Record any set of buses to disk, and a window to do it from.** A
SuperCollider extension.

Point it at buses - by index, or by name when BusManager is installed - and
write them however you like: one multichannel file, stereo pairs, one file per
source, one file per channel. Every file in a take starts on the same sample.
Files are named after the take with the source's name, or its bus index,
appended. Meter each source, listen to it before you commit, and save the whole
setup as a layout.

```supercollider
s.boot;
BusRecorder.add(\main, 0, 2);        // bus 0, two channels
BusRecorder.add(\drums, 16, 2);      // bus 16
BusRecorder.mode = \sources;         // one file per source
BusRecorder.fileName = "take";
BusRecorder.record;                  // take_001_main.wav, take_001_drums.wav
BusRecorder.stop;

BusRecorder.gui;                     // add, arm, meter, listen, record
```

It depends on nothing - no plugins, no other quarks. If **BusManager** happens
to be installed alongside it, a source can be added by bus name and follows
that name from then on - see [Sources](#sources) below.

Written for SuperCollider 3.13 on macOS. Nothing in it is platform specific
apart from the symlink in `install.scd`, which Linux shares and Windows does not.

## Install

### As a quark

```supercollider
Quarks.install("https://github.com/fracnesco/sc-recorder.git");
thisProcess.recompile;
```

`Quarks.install` clones the repository into your `downloaded-quarks` folder and
puts it on the class path; the recompile is what makes the classes exist, and is
the same thing as `Language -> Recompile Class Library` (ctrl/cmd + shift + L).

Afterwards:

```supercollider
Quarks.update("sc-recorder");         // pull the newest version, then recompile
Quarks.uninstall("sc-recorder");      // take it off the class path
Quarks.gui;                           // see what is installed
```

The quark goes by the name of the repository, `sc-recorder`; the class inside
it is `BusRecorder`.

### From a clone

If you would rather have a working copy you can edit, clone it wherever you keep
things and link it into the extensions folder:

```sh
git clone https://github.com/fracnesco/sc-recorder.git
```

There is an `install.scd` in the repository that does the linking for you: open
it and evaluate it, then recompile the class library. It symlinks the folder
into `Platform.userExtensionDir` and tells you what it did.

On Windows there is no `ln -s`, so use the quark, or copy the folder into the
extensions folder by hand - `Platform.userExtensionDir.openOS` opens it.

Install it one way or the other, **not both**: two copies on the class path is a
duplicate class error at compile time.

### Check that it took

```supercollider
BusRecorder.dir;     // where the files will go
BusRecorder.gui;     // the window
```

Select `BusRecorder` and press cmd/ctrl + d for the help file, and see
[examples/bus-recorder-demo.scd](examples/bus-recorder-demo.scd) for a
walkthrough you can evaluate a block at a time.

## Sources

One recorder per session, the way there is one server. Everything is on the
class - there is no instance to keep hold of. A source is a name and a way of
finding a bus, and there are three ways:

```supercollider
BusRecorder.add(\main, 0, 2);            // a bus index and a channel count
BusRecorder.addIndex(16, 2);             // the same, named bus16 for you

BusRecorder.add(\drums);                 // a BusManager name; the source is called drums too
BusRecorder.add(\kick, \drums, 1);       // the same bus, first channel only, called kick

BusRecorder.addBus(someModule.tapBus);   // a Bus something else made
```

| kind | made by | found again by |
|------|---------|----------------|
| `\index` | `add` with a number, `addIndex` | the index you gave |
| `\name` | `add` with a symbol, `addName` | asking BusManager, every time its buses change |
| `\bus` | `addBus` | the Bus itself |

Nothing is ever allocated or freed: the recorder only reads. That is what makes
it safe to point at anything - hardware inputs, the main out, a bus the reverb
lives on.

A source added by name **follows the name**. When the bus behind it moves -
which it does when the server reboots - the source goes with it, even in the
middle of a take. A name BusManager does not know, or BusManager not being
installed at all, leaves the source in the list but unresolved: it shows a `?`
in the window and is left out of the take until the name turns up.

```supercollider
BusRecorder.names;                  // every source, in the order added
BusRecorder.at(\drums);             // the source, with its index and channels
BusRecorder.rename(\drums, \kit);
BusRecorder.remove(\kit);
BusRecorder.clear;
```

Re-using a name replaces what was there, so re-evaluating a setup block does
not pile up sources.

### Arming

Whether a source goes into the next take. A disarmed source stays in the list,
metered and listenable, just not written - the way to leave the click track out
without losing its row.

```supercollider
BusRecorder.disarm(\click);
BusRecorder.arm(\click);
```

## The take

```supercollider
BusRecorder.record;        // every file starts on the same sample
BusRecorder.pause;         // nothing is written while paused
BusRecorder.resume;
BusRecorder.stop;          // the post window lists what was written
BusRecorder.record(30);    // or give it a length, and it stops itself

BusRecorder.duration;      // seconds written so far, or the length of the last take
BusRecorder.files;         // the paths of the take in progress, or the last one
```

Each file has its own disk synth, and they are started in one bundle, so a
take's files line up to the sample however many there are. Which sources go in
and what they are called is decided when `record` runs; anything you change
afterwards is for the next take.

### How it is split into files

`mode` decides how the armed sources' channels are cut into files:

| mode | what you get |
|------|--------------|
| `\single` | every channel of every source in one multichannel file |
| `\pairs` | the same channels, cut into stereo files two at a time |
| `\sources` | one file per source, however many channels it has |
| `\mono` | one file per channel |

Say you have `main` (2 channels), `quad` (4) and `kick` (1), and the take is
called `take`:

```
\single    take_001.wav                          (7 channels)
\pairs     take_001_main.wav  take_001_quad_1-2.wav  take_001_quad_3-4.wav  take_001_kick.wav
\sources   take_001_main.wav  take_001_quad.wav      take_001_kick.wav
\mono      take_001_main_1.wav  take_001_main_2.wav  take_001_quad_1.wav ...  take_001_kick.wav
```

A file that holds part of a source says which part, counting from 1. A stereo
pair made of two mono sources is labelled with both: `take_001_kick+snare.wav`.

### What the files are called

The name you give, the take number, then the sources in the file:

```supercollider
BusRecorder.fileName = "take";       // the first part of every file name
BusRecorder.label = \name;           // ... _drums.wav - the source's name
BusRecorder.label = \index;          // ... _bus16.wav - the index of its bus
BusRecorder.numbering = \take;       // take_001_..., and the next is 002
BusRecorder.numbering = \timestamp;  // take_260824_213000_...
BusRecorder.numbering = \none;       // take_... - and the next take overwrites it

BusRecorder.nextPaths;               // what the next take would write - check before you press
```

The take number is one more than the highest already in the folder under that
name, so it carries on where you left off after a restart. Two sources that
would be labelled alike - two views of one bus, labelled by index - get a `_2`
rather than the same file.

### Where and in what format

```supercollider
BusRecorder.dir;                          // SuperCollider's own recordings folder, unless set
BusRecorder.dir = "~/Music/piece/takes";  // made when the first take starts
BusRecorder.headerFormat = "wav";         // wav, aiff, caf
BusRecorder.sampleFormat = "int24";       // int16, int24, float
```

## Watching a source

Two ways to find out what is in a source before you record it: look at it, or
listen to it.

```supercollider
BusRecorder.meter_(true);            // start metering
BusRecorder.level(\drums);           // -> 0.34, the loudest channel right now
BusRecorder.at(\drums).levels;       // -> [0.34, 0.31], one per channel

BusRecorder.listen(\drums);          // route it to the monitor output and hear it
BusRecorder.listenOff;
BusRecorder.monitorOut = 0;          // where listening goes, 0 by default
BusRecorder.monitorAmp = 0.5;
```

Metering is one small synth per source, replying fifteen times a second, and
it is **off until something asks for it** - opening the window turns it on and
closing the window turns it off again. Listening is routing, so it stays up
until you stop it, window or no window.

Only one source is listened to at a time: starting another stops the first.
**Listening to a source that overlaps the monitor output is refused** rather
than played, because that is a feedback loop - which is what clicking listen on
the main out would otherwise be. That is the one thing this extension has to
get right on its own, and it does not bend.

## The window

`BusRecorder.gui`.

```
 recorder  [record] [pause] 00:12.3          monitor out [ 0 ]  level ----  [x] meters  save layout  load layout
 file [take      ] in [Recordings] [open]  as [one per source]  [by name]  [take number]  [wav] [int24]
 --------------------------------------------------------------------------------------------------
  name        bus        ch   index   level                       arm   listen   x
  main        index       2       0   ||||......                  [x]    ( )     x
  drums       drums       2      16   ||........                  [x]    (o)     x
  click       click       1      18   ..........                  [ ]    ( )     x
 --------------------------------------------------------------------------------------------------
  add   [ name      ]   bus [ drums   v]   index [ 16 ]   ch [ 2 ]   [ add ]
  3 sources · metering · next take: 2 files - take_004_main.wav, take_004_drums.wav
```

- **record** starts a take and turns red; click it again to stop. **pause**
  holds it. The clock is the time written so far.
- The **file** row is how the take is written: base name, the folder (click it
  to choose another, **open** shows it in the Finder), how it is split, what
  the files are labelled with, how takes are numbered, and the format.
- **name** is editable - type a new one and press enter to rename the source.
  **bus** is where it comes from: a BusManager name, or `index`.
- **level** is one bar per channel, so you can see which side is sounding. An
  **index** of `?` means the source is not pointing at a bus right now.
- **arm** decides whether the source goes into the next take.
- **listen** routes that source to the monitor output; click it again to stop.
- **x** removes the source.
- The **add** row: a name, then either `index` and a number, or a BusManager
  bus from the dropdown. Leave the name empty and it is named after the bus.
  Picking a name fills in the channel count; lower it to record fewer.
- The status line says what the next take would write. Hover it for the full
  paths.

## Layouts

The sources and every setting - name, folder, mode, label, numbering, format,
monitor - saved to a file so a rig comes back the way you left it. They live in
the `layouts/` folder next to the class files, wherever the extension ended up
- inside `downloaded-quarks/sc-recorder/` for a quark install, inside the clone
for a symlinked one. A bare name goes there and gets an `.scd` extension; a
path with a slash in it is used as given, which is the way to keep a layout
with a piece rather than with the extension.

```supercollider
BusRecorder.saveLayout("tonight");     // -> <extension>/layouts/tonight.scd
BusRecorder.loadLayout("tonight");
BusRecorder.saveLayout("~/Music/piece/recorder.scd");
BusRecorder.layouts;                   // what the window's dropdown offers
BusRecorder.layoutDir;
```

Loading replaces everything. A source added by name is saved as the name, so it
finds its bus again on whatever rig loads it; a source pointing at a Bus another
object made is saved as the index it had, since a file cannot find that Bus
again, and the post window says so.

Loading is always explicit. Nothing is restored at startup by itself. Saved
layouts are ignored by git, so updating the extension does not touch them.

## Server restarts

The recorder registers with `ServerBoot`, `ServerTree` and `ServerQuit`:

- a **boot** sends the synth definitions again, and every source added by name
  looks its bus up again - BusManager re-takes its buses on the same hook, and
  announces the result, so the two agree whichever ran first;
- **cmd + period** takes every node, the disk synths included. The files are
  closed so they get their headers, the post window says the take was cut
  short, and the meters and the monitor are rebuilt;
- if the **server itself dies** with a take open, the files are left without
  headers. Nothing can be done about that from the language side.

## Files

| file | what it holds |
|------|---------------|
| [classes/BusRecorder.sc](classes/BusRecorder.sc) | the recorder: sources, the plan, the take, meters, listening, layouts, server lifecycle |
| [classes/BusRecorderSource.sc](classes/BusRecorderSource.sc) | one source, and how its bus is found |
| [classes/BusRecorderGUI.sc](classes/BusRecorderGUI.sc) | the window |
| [HelpSource/Classes/BusRecorder.schelp](HelpSource/Classes/BusRecorder.schelp) | the help file, cmd/ctrl + d on the class name |
| [examples/bus-recorder-demo.scd](examples/bus-recorder-demo.scd) | a walkthrough you can evaluate block by block |
| [install.scd](install.scd) | symlinks the repository into the extensions folder |
| [sc-recorder.quark](sc-recorder.quark) | the quark manifest, named after the repository so `Quarks` can find it |

## Issues

Bug reports and questions are welcome in
[Issues](https://github.com/fracnesco/sc-recorder/issues). If something
misbehaves, the post window usually has the half of the story I need.

## License

MIT - see [LICENSE](LICENSE).
