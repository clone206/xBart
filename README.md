# xBart (Cross-platform Batch Audio Resampler and Transcoder) 
A batch audio file converter/resampler, written in java.

This software is meant to work only with audio files. It is mainly designed to help in situations where you have multiple lossless audio files in one or many format/sample rate(s), and you want to convert them all to one particular format/sample rate. It supports a specific target sample rate for all files, or you can set a maximum sample rate, and the closest even multiple of 44.1K or 48K below the specified maximum will be calculated and set for you.

For example, your phone may only support certain audio file formats, and it may have limitations as to which sample rates it supports. If you attempt to play back an audio file with a sample rate that is too high for your phone, it may get resampled "on-the-fly" by your phone, and the results may be less than pleasing to the discerning ear. Or perhaps you have Super Audio CD (SACD) rips in DSD format (.dff or .dsf files are supported), and you want to convert them to PCM files, because you don't have a DSD-capable Digital-to-Analog Converter (DAC), and you don't want these files converted "on-the-fly" by whatever player software you're using.

By default a directory is created in your home directory called "batch_resampled" (~/batch_resampled in *nix, for example), but you may want to change this by modifying the OUT_DIR constant and recompiling.

Makes use of [javacpp-presets/ffmpeg](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) JNI bindings for [ffmpeg](https://www.ffmpeg.org/).

Depends on [maven](https://maven.apache.org) for building.

## Building 

### Jars with dependencies for the currently running OS:

`mvn package`

### Jars with dependencies for all supported OS's:

`mvn package -Dall_platforms`

## Running

With audio files added to the cloned directory:

`java -jar target/xbart.jar <sample_rate> <outfile_extension> [vol_adjust_db]`

Converts all supported audio files in the current directory to the format corresponding
to the given file extension (don't include the dot), at the specified sample rate (in Hz).
To specify a maximum output sample rate, where any input file of a greater rate gets downsampled
to the nearest even multiple of either 44100 or 48000, add an 'm' to the end of the number,
eg. '96000m'. If an input file has a sample rate that is already below this, it will not be upsampled.

An optional volume adjust (in dB) can be given (positive number for boost,
negative for cut). Comes in handy when converting DSD to PCM.

Renames file basenames on conversion and doesn't re-convert already
converted files on subsequent runs.

Supported infile types: flac,dsf,dff,wav,aiff,m4a,mp3
Supported outfile types: flac,wav,aiff,m4a(alac),mp3

Each converted file gets an "ff\d+k" appended to the file's basename, where "\d+" is the sample rate of the converted file in kHz. If you run the script multiple times, and use the same output directory, any already converted file will be skipped, as long as you're specifying the same sample rate and file format (flac, m4a, etc) as on previous runs. This way you can keep adding files to your library and runnning the script again as needed.

When xBart is working recursively, the directory structure from the input directory is copied to the output directory.

#### examples

Set all to 48K flac files with a 3dB boost:


```java -jar [path_to_xbart]xbart.jar 48000 flac 3```

Apple Lossless files with a maximum sample rate of 96K. Rounds down to the closest even multiple of the input file's sample rate:


```java -jar [path_to_xbart]xbart.jar 96000m m4a```

## Wonky Q & A
#### What if I add more audio files to the same directory on my computer where the already-converted files live? Since xBart is recursive, will it go through and re-convert all the files again?
It depends. xBart tries to be smart about this. If you specify a new format/sample rate the next time you run the script, even if you use the same output directory as last time (OUT_DIR), then yes, all of the files will get converted again. The script appends a special marker to each converted file name showing what sample rate it was converted to. If you request a conversion to a certain sample rate and file type, and xBart sees that there's already a non-empty file in the specified output directory that meets the description of the requested conversion, it skips the conversion on that run. But if you're using the same settings and output directory as last time, then only the newly added files will be converted, and as usual, the directory structure from the source directory gets copied over to the output directory.

#### So why do I care if the sample rate gets converted to an even multiple of the original sample rate?
Because otherwise you have to use "interpolation" to guess at where the new samples' amplitudes should be. In theory this can be done extremely transparently, but in practice there are many variables that determine how the resultant audio sounds. If you're converting between lossless formats, I'm assuming you care about this stuff. Just for fun, have a look [here](http://src.infinitewave.ca). You'll see a wide array of differences in how audio comes out after converting to an uneven multiple of the original sample rate. I know, I know. You can't go by graphs. What matters is whether a human can hear the differences. That's another long discussion.

You can also end up with smaller file sizes when setting a maximum/even multiple mode, because then some files have their sample rates rounded down, which means the files take up less storage space.

#### Can I set the output directory to the sd card/internal storage of my usb-connected android phone?
Yes! But your mileage may vary. This has been tested successfully on ubuntu with the phone mounted via [MTP](https://en.wikipedia.org/wiki/Media_Transfer_Protocol).

MTP can be kind of a pain, though. For one thing you have to [find out where the phone gets mounted](https://askubuntu.com/a/342549) into your linux filesystem, and it can change each time you plug your phone back into the usb port. Also, you may get done with a huge conversion, browse to your phone's mounted storage in ubuntu and see nothing but "0K" (empty) files. Don't panic. It's an MTP thing. Safely unmount/eject your phone, unplug and plug it back in, and the files should show the correct sizes now.

xBart checks to see if it's already created the requested file in the specified output directory (OUT_DIR), and whether or not it's empty. If there's a non-empty file there, and you run the script again, the conversion of that file will get skipped, but not if you're trying to write to an MTP-mounted phone that's still reporting the file as being "0K". The conversions will all happen again. In that case you'll have to follow the above unplugging/replugging procedure before rerunning xBart.

