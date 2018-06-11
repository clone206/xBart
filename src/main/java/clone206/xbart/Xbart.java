/* Copyright 2018 Kevin Witmer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package clone206.xbart;

import java.io.*;
import java.nio.file.*;
import org.bytedeco.javacpp.*;
import java.util.*;

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avcodec.AVCodecContext.FF_COMPLIANCE_EXPERIMENTAL;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.avfilter.*;

/**
 * A cross platform desktop app for batch resampling and transcoding of audio files,
 * with an option to boost/cut volume of the audio.
 *
 * @author Kevin Witmer
 *
 * @file
 * Cross-platform Batch Audio Resampler and Transcoder
 */
public class Xbart {
    /* CONSTANTS */
    public static final int MIN_SR          = 44100;
    public static final Path OUT_PATH       = Paths.get( System.getProperty("user.home") ).resolve("batch_resampled");
    /* Relative path to CWD */
    public static final Path CWD            = Paths.get( System.getProperty("user.dir") )
        .relativize( Paths.get(System.getProperty("user.dir")) );
    /* The output bit rate in kbit/s, applies to mp3 only for now */
    public static final int OUTPUT_BIT_RATE = 320000;

    /* GLOBALS */
    public static int max_sr                = 0;
    public static String args_sr            = "";
    public static String outfile_ext        = "";
    public static String vol_boost          = "";
    public static String filter_descr;
    static AVFormatContext inp_fmt_ctx      = new AVFormatContext(null),
                           out_fmt_ctx      = new AVFormatContext(null);
    static AVCodecContext dec_ctx           = new AVCodecContext(null),
                          enc_ctx           = new AVCodecContext(null);
    static AVFilterContext buffersink_ctx   = new AVFilterContext(),
                           buffersrc_ctx    = new AVFilterContext();
    static AVFilterGraph filter_graph       = new AVFilterGraph();
    static int audio_stream_index           = -1;
    static AVCodec output_codec             = new AVCodec(null);
    /* Global timestamp for the audio frames */
    static long pts                         = 0;
    static boolean error                    = false;

    public static void printUsage () {
            System.err.println("");
            System.err.println("USAGE: java -jar xbart.jar <sample_rate> <outfile_ext> [vol_adjust_db]");
            System.err.println("");
            System.err.println(
                "Converts all supported audio files in the current directory to the format corresponding "
            );
            System.err.println(
                "to the given file extension (don't include the dot), at the speciied sample rate (in Hz). "
            );
            System.err.println(
                "To specify a maximum output sample rate, where any input file of a greater rate gets downsampled "
            );
            System.err.println(
                "to the nearest even multiple of either 44100 or 48000, add an 'm' to the end of the number, "
            );
            System.err.println(
                "eg. '96000m'. If an input file has a sample rate that is already below this, it will not be upsampled. "
            );
            System.err.println("");
            System.err.println("An optional volume adjust (in dB) can be given (positive number for boost, ");
            System.err.println("negative for cut). ");
            System.err.println("");
            System.err.println("Renames file basenames on conversion and doesn't re-convert already ");
            System.err.println("converted files on subsequent runs.");
            System.err.println("");
            System.err.println("Supported infile types: flac,dsf,dff,wav,aiff,m4a,mp3");
            System.err.println("Supported outfile types: flac,wav,aiff,m4a(alac),mp3");
            System.err.println("");
    }

    /* Custom implementation of missing av_err2str() ffmpeg function */
    static String my_av_err2str (int err) {
        BytePointer e = new BytePointer(512);
        av_strerror(err, e, 512);
        return e.getString().substring(0, (int) BytePointer.strlen(e));
    }
    
    /* Check for error code returned by ffmpeg func and throw error */
    static void check (int err) {
        if (err < 0) {
            throw new RuntimeException(my_av_err2str(err) + ":" + err);
        }
    }

    /* Open an input file and the required decoder. */
    static void openInputFile (String filename) {
        AVCodec dec = new AVCodec();
        
        /* Open the input file to read from it. */
        check( avformat_open_input(inp_fmt_ctx, filename, null, null) );
        /* Get information on the input file (number of streams etc.). */
        check( avformat_find_stream_info(inp_fmt_ctx, (PointerPointer) null) );

        /* Select the audio stream */
        check( audio_stream_index = av_find_best_stream(inp_fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, dec, 0) );

        /* Create decoding context */
        dec_ctx = avcodec_alloc_context3(dec);

        if (dec_ctx.isNull()) {
            throw new RuntimeException( "Error: " + my_av_err2str(AVERROR_ENOMEM()) );
        }

        check( avcodec_parameters_to_context(dec_ctx, inp_fmt_ctx.streams(audio_stream_index).codecpar()) );
        av_opt_set_int(dec_ctx, "refcounted_frames", 1, 0);

        /* Init the audio decoder */
        check( avcodec_open2(dec_ctx, dec, (AVDictionary) null) );
    }

    /*
     * Open the output file and set the encoder context fields accordingly
     */
    static void openOutputFile (String filename) {
        AVIOContext output_io_context = new AVIOContext(null);
        AVStream stream = new AVStream(null);
        Hashtable<String, Integer> codecs = new Hashtable<String, Integer>() {
            {
                put("flac", AV_CODEC_ID_FLAC);
                put("m4a", AV_CODEC_ID_ALAC);
                put("mp3", AV_CODEC_ID_MP3);
            }
        };
        String dec_name = dec_ctx.codec().name().getString();

        /* Determine original bit depth. Explicitly set it to 24 bits if we have a DSD file as input */
        int input_bits = (dec_name.matches("^dsd_.*") || dec_name.equals("dst"))
            ? 24
            : dec_ctx.bits_per_raw_sample();

        /* Multiple codecs for certain file extensions, depending on bit depth */
        if (outfile_ext.equals("wav")) {
            switch (input_bits) {
                case 64: codecs.put("wav", AV_CODEC_ID_PCM_S64LE);
                         break;
                case 32: codecs.put("wav", AV_CODEC_ID_PCM_S32LE);
                         break;
                case 24: codecs.put("wav", AV_CODEC_ID_PCM_S24LE);
                         break;
                case 16: codecs.put("wav", AV_CODEC_ID_PCM_S16LE);
                         break;
                case 8:  codecs.put("wav", AV_CODEC_ID_PCM_S8);
                         break;
                default: codecs.put("wav", AV_CODEC_ID_PCM_S16LE);
                         break;
            }
        }

        if (outfile_ext.equals("aiff")) {
            switch (input_bits) {
                case 64: codecs.put("aiff", AV_CODEC_ID_PCM_S64BE);
                         break;
                case 32: codecs.put("aiff", AV_CODEC_ID_PCM_S32BE);
                         break;
                case 24: codecs.put("aiff", AV_CODEC_ID_PCM_S24BE);
                         break;
                case 16: codecs.put("aiff", AV_CODEC_ID_PCM_S16BE);
                         break;
                case 8:  codecs.put("aiff", AV_CODEC_ID_PCM_S8);
                         break;
                default: codecs.put("aiff", AV_CODEC_ID_PCM_S16BE);
                         break;
            }
        }

        /* Create a new format context for the output container format. */
        check( avformat_alloc_output_context2(out_fmt_ctx, null, null, filename) );
        /* Open the output file to write to it. */
        check( avio_open(output_io_context, filename, AVIO_FLAG_WRITE) );
        /* Associate the output file (pointer) with the container format context. */
        out_fmt_ctx.pb( output_io_context );

        /* Find the encoder to be used by its name. */
        if ( (output_codec = avcodec_find_encoder(codecs.get(outfile_ext))).isNull() ) {
            throw new RuntimeException("Could not find an appropriate encoder");
        }
        
        /* Create a new audio stream in the output file container. */
        if ( (stream = avformat_new_stream(out_fmt_ctx, null)).isNull() ) {
            throw new RuntimeException("Could not create new stream");
        }

        if ( (enc_ctx = avcodec_alloc_context3(output_codec)).isNull() ) {
            throw new RuntimeException("Could not allocate an encoding context");
        }

        /*
         * Set the basic encoder parameters.
         * The input file's sample rate is used to avoid a sample rate conversion.
         */
        enc_ctx.channels(dec_ctx.channels());
        enc_ctx.channel_layout( av_get_default_channel_layout(dec_ctx.channels()) );
        enc_ctx.sample_rate(max_sr);

        /* Handle cases where a codec supports multiple sample formats */
        if ( outfile_ext.equals("flac") ) {
            switch (input_bits) {
                case 64:
                case 32:
                case 24: enc_ctx.sample_fmt(AV_SAMPLE_FMT_S32);
                         break;
                case 16: enc_ctx.sample_fmt(AV_SAMPLE_FMT_S16);
                         break;
                default: enc_ctx.sample_fmt(output_codec.sample_fmts().get(0));
                         break;
            }  
        }
        else if ( outfile_ext.equals("m4a") ) {
            switch (input_bits) {
                case 64:
                case 32:
                case 24: enc_ctx.sample_fmt(AV_SAMPLE_FMT_S32P);
                         break;
                case 16: enc_ctx.sample_fmt(AV_SAMPLE_FMT_S16P);
                         break;
                default: enc_ctx.sample_fmt(output_codec.sample_fmts().get(0));
                         break;
            }  
        }
        else {
            enc_ctx.sample_fmt(output_codec.sample_fmts().get(0));
        }

        if ( outfile_ext.equals("mp3") ) {
            enc_ctx.bit_rate(OUTPUT_BIT_RATE);
        }

        /* Allow the use of the experimental AAC encoder */
        enc_ctx.strict_std_compliance(FF_COMPLIANCE_EXPERIMENTAL);

        /* Set the sample rate for the container. */
        stream.time_base(new AVRational());
        stream.time_base().den( max_sr );
        stream.time_base().num( 1 );

        /*
         * Some container formats (like MP4) require global headers to be present
         * Mark the encoder so that it behaves accordingly.
         */
        if ( (out_fmt_ctx.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
            enc_ctx.flags( enc_ctx.flags() | AV_CODEC_FLAG_GLOBAL_HEADER );
        }

        /* Open the encoder for the audio stream to use it later. */
        System.err.println("Opening the encoder.");
        check( avcodec_open2(enc_ctx, output_codec, (AVDictionary) null) );
        /* Initialize stream params */
        check( avcodec_parameters_from_context(stream.codecpar(), enc_ctx) );
    }

    /* 
     * Initialize the filter chain based on the description string, configuring the src and sink
     * so that they are compatible with the decoder and encoder, respectively
     */
    static void initFilters () {
        AVFilterInOut outputs = avfilter_inout_alloc(),
                      inputs  = avfilter_inout_alloc();
        AVFilterLink outlink  = new AVFilterLink(),
                     inlink   = new AVFilterLink();
        AVRational time_base = inp_fmt_ctx.streams(audio_stream_index).time_base();
        filter_graph = avfilter_graph_alloc();
        String filters_descr;

        if (outputs.isNull() || inputs.isNull() || filter_graph.isNull()) {
            throw new RuntimeException(my_av_err2str( AVERROR_ENOMEM() ) + ":" + AVERROR_ENOMEM());
        }

        try {
            /* 
             * Some pointers to arrays for setting binary filter options for the buffersink.  
             * These should match our encoder sample format/ch layout/sample rate
             */
            BytePointer out_sample_fmts = new BytePointer(8);
            out_sample_fmts.putInt(0, enc_ctx.sample_fmt());
            out_sample_fmts.putInt(4, -1);

            BytePointer out_channel_layouts = new BytePointer(16);
            out_channel_layouts.putLong(0, enc_ctx.channel_layout());
            out_channel_layouts.putLong(8, -1);
            
            BytePointer out_sample_rates = new BytePointer(8);
            out_sample_rates.putInt(0, enc_ctx.sample_rate());
            out_sample_rates.putInt(4, -1);

            /* Buffer audio source: the decoded frames from the decoder will be inserted here. */ 
            if (dec_ctx.channel_layout() == 0) {
                dec_ctx.channel_layout( av_get_default_channel_layout(dec_ctx.channels()) );
            }

            /* The buffersrc sample fmt, sample rate, and ch layout should match decoder output */
            filters_descr = (String.format(
                        "abuffer@in=time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=0x%x,",
                        time_base.num(), time_base.den(), dec_ctx.sample_rate(),
                        av_get_sample_fmt_name(dec_ctx.sample_fmt()).getString(), dec_ctx.channel_layout()
            )) +  "aresample=" + max_sr + (vol_boost.length() > 0 ? (",volume=" + vol_boost) : "") + ",abuffersink@out";

            /* Add a graph described by a string. */
            check( avfilter_graph_parse2(filter_graph, filters_descr, inputs, outputs) );

            /* Get buffer contexts from parsed graph */
            buffersink_ctx = avfilter_graph_get_filter(filter_graph, "abuffersink@out");
            buffersrc_ctx = avfilter_graph_get_filter(filter_graph, "abuffer@in");

            /* Set binary options for buffersink */
            check( av_opt_set_bin(buffersink_ctx, "sample_fmts", out_sample_fmts, 4, AV_OPT_SEARCH_CHILDREN) );
            check( av_opt_set_bin(buffersink_ctx, "channel_layouts", out_channel_layouts, 8, AV_OPT_SEARCH_CHILDREN) );
            check( av_opt_set_bin(buffersink_ctx, "sample_rates", out_sample_rates, 4, AV_OPT_SEARCH_CHILDREN) );

            /* Set correct frame size on buffersink so that it matches that of the encoder */
            av_buffersink_set_frame_size(buffersink_ctx, enc_ctx.frame_size());

            /* Check validity and configure all the links and formats in the graph. */
            check( avfilter_graph_config(filter_graph, null) );

            /* Print summary of the src and sink buffers */
            inlink = buffersrc_ctx.outputs(0);
            outlink = buffersink_ctx.inputs(0);

            System.err.println(
                "Input: srate:" + inlink.sample_rate() + "Hz fmt:" 
                + ( av_get_sample_fmt_name( inlink.format() ) ).getString()
                + " Channels num: " + inlink.channels()
            );
            System.err.println(
                "Output: srate:" + outlink.sample_rate() + "Hz fmt:" 
                + ( av_get_sample_fmt_name( outlink.format() ) ).getString()
                + " Channels num: " + outlink.channels()
            );
        }
        finally {
            avfilter_inout_free(inputs);
            avfilter_inout_free(outputs);
        }
    }

    /* Convert the input filename into a suitable output filename */
    static String filenameConv (Path p, String suffix) {
        return p.toString().replaceFirst(
            "\\.(?:flac|dsf|dff|wav|aiff|m4a|mp3)$", 
            suffix + "." + outfile_ext
        );
    }

    /* Transcode the already-open infile, writing to the given outfile path */
    static void transcode (String outfile_path) {
        AVPacket input_packet   = new AVPacket(),
                 output_packet  = new AVPacket();
        AVFrame frame           = av_frame_alloc(),
                filt_frame      = av_frame_alloc();
        int ret                 = 0;

         if (frame.isNull() || filt_frame.isNull()) {
             throw new RuntimeException("Could not allocate frame");
         }
             
         try {
             System.err.println("Opening output file: " + outfile_path);
             openOutputFile(outfile_path);
             
             initFilters();

             /* Write the header of the output file container. */
             check( avformat_write_header(out_fmt_ctx, (AVDictionary) null) );

             System.err.println("Transcoding...");
             /* Read all packets */
             while (true) {
                 if ( (ret = av_read_frame(inp_fmt_ctx, input_packet)) < 0) {
                     break;
                 }

                 if (input_packet.stream_index() == audio_stream_index) {
                     if ( (ret = avcodec_send_packet(dec_ctx, input_packet)) < 0) {
                         System.err.println("Error while sending a packet to the decoder");
                         break;
                     }

                     while (ret >= 0) {
                         /* Push the audio data from decoded frame into the filtergraph */
                         ret = avcodec_receive_frame(dec_ctx, frame);

                         if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
                             break;
                         }
                         else if (ret < 0) {
                             throw new RuntimeException("Error while receiving frame from the decoder");
                         }

                         if (ret >= 0) {
                             /* Push the audio data from decoded frame into the filtergraph */
                             if (av_buffersrc_add_frame_flags(buffersrc_ctx, frame, AV_BUFFERSRC_FLAG_KEEP_REF) < 0) {
                                 System.err.println("Error while feeding the audio filtergraph");
                                 break;
                             }

                             /* Pull filtered audio from the filtergraph */
                             while (true) {
                                 ret = av_buffersink_get_frame(buffersink_ctx, filt_frame);

                                 if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
                                     break;
                                 }
                                 if (ret < 0) {
                                     throw new RuntimeException("Couldn't get frame from filtergraph");
                                 }
                                 /* Set the packet data and size so that it is recognized as being empty. */
                                 output_packet.data(null);
                                 output_packet.size(0);
                                 av_init_packet(output_packet);

                                 if ( !filt_frame.isNull() ) {
                                     filt_frame.pts( pts );
                                     pts += filt_frame.nb_samples();
                                 }

                                 check( avcodec_send_frame(enc_ctx, filt_frame) );

                                 while (true) {
                                     ret = avcodec_receive_packet(enc_ctx, output_packet);

                                     if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF) {
                                         break;
                                     }
                                     if (ret < 0) {
                                         throw new RuntimeException("Couldn't read data from the encoder");
                                     }

                                     /* Write one audio frame from the encoded packet to the output file. */
                                     try {
                                         check( ret = av_write_frame(out_fmt_ctx, output_packet) );
                                     }
                                     finally {
                                         av_packet_unref(output_packet);
                                     }
                                 }
                                 av_frame_unref(filt_frame);
                             }
                             av_frame_unref(frame);
                         }
                     }
                 }
                 av_packet_unref(input_packet);
             }
             /* Flush buffers */
             check( avcodec_send_packet(dec_ctx, null) );
             check( avcodec_send_frame(enc_ctx, null) );

             /* Write the trailer of the output file container. */
             check( av_write_trailer(out_fmt_ctx) );
        }
        /* Cleanup */
        finally {
            avfilter_graph_free(filter_graph);
            avcodec_free_context(enc_ctx);
            avformat_close_input(out_fmt_ctx);
            av_frame_free(frame);
            av_frame_free(filt_frame);

            if (ret < 0 && ret != AVERROR_EOF) {
                System.err.println("Error occurred: " + my_av_err2str(ret));
                error = true;
            }

        }

    }

    static int recalcSR (int infile_sr, int lowest_factor) {
        int this_max_sr = Integer.parseInt( args_sr.replaceFirst("m$", "") );

        if ( (this_max_sr % 44100) != 0 && (this_max_sr % 48000) != 0 ) {
            System.err.println("Error in user-provided sample rate");
            throw new RuntimeException(
                "Only even multiples of 44100 and 48000 are allowed to be specified for maximum output sample rates!"
            );
        }

        if (infile_sr < this_max_sr) {
            System.err.println("Input sample rate <= output rate. Will not be upsampled.");
            return infile_sr;
        }
        else if (this_max_sr == MIN_SR) {
            System.err.println(
                "No even multiple available below minimum rate of " + MIN_SR + ", so downsampling to " + MIN_SR + "."
            );
            return this_max_sr;
        }
        else {
            System.err.println("Downsampling to even multiple of " + lowest_factor);
            return (this_max_sr / lowest_factor) * lowest_factor;
        }
    }

    static int findLowestFactor (int infile_sr) {
        if (infile_sr != 0 && infile_sr % 44100 == 0) {
            return 44100;
        }
        else if (infile_sr != 0 && infile_sr % 48000 == 0) {
            return 48000;
        }
        else {
            System.err.println(
                "ERROR: Only multiples of 44100 and 48000 are allowed for input sample rates when in maximum mode!"
            );
            return 0;
        }
    }

    /* Convert a file at the specified path */
    static void conv (Path p) {
        Path op                 = OUT_PATH.resolve(p).normalize();
        String infile_path      = p.toString();
        int lowest_factor       = 0;
        String suffix           = "";

         try {
             av_register_all();
             avfilter_register_all();

             System.err.println("Opening input file: " + infile_path);
             openInputFile(infile_path);

             /* If user appended an "m" for "maximum" to the end of
              * the sample rate param, find nearest even multiple of
              * this infile's sample rate
              */
             if ( args_sr.matches(".*m$") ) {
                 System.err.println("Maximum sample rate specified for output. Recalculating destination sample rate");

                 /* Get lowest factor from infile sr and skip this file on error */
                 if ( (lowest_factor = findLowestFactor(dec_ctx.sample_rate())) == 0 ) {
                     System.err.println("SKIPPING " + infile_path + "...");
                     return;
                 }
                 max_sr = recalcSR(dec_ctx.sample_rate(), lowest_factor);
             }
             else {
                 max_sr = Integer.parseInt(args_sr);
             }

             suffix = "_ff" + (max_sr / 1000) + "k";
             String outfile_path     = filenameConv(op, suffix);
             File of                 = new File(outfile_path);
         
             if ( of.exists() ) {
                System.err.println("Output file already exists!");
                System.err.println("SKIPPING " + outfile_path);
             }
             else {
                 transcode(outfile_path);
             }
         }
         /* Cleanup */
         finally {
             avcodec_free_context(dec_ctx);
             avformat_close_input(inp_fmt_ctx);
         }
    }

    /* Make a single directory from a given path */
    static void mkdirP (Path dir) {
        try {
            Files.createDirectories(dir);
        }
        catch(IOException e) {
            error = true;
        }
    }

    /* Creates the output dir structure */
    static void mkDirsIfNotEx () throws IOException {
        Files.find(
            CWD,
            999,
            (p, bfa) -> bfa.isDirectory() && p.toString().length() > 1 
        ).forEach( 
            p -> mkdirP( OUT_PATH.resolve(p).normalize() ) 
        );
    }

    public static void main (String[] args) throws IOException {
        /* Args sanity check */
        if (args.length < 2) {
            printUsage();
            System.exit(-1);
        }

        /* Set some globals */
        args_sr = args[0];
        outfile_ext = args[1];

        /* Check for/set volume boost arg */
        if (args.length >= 3 && args[2].length() > 0) {
            vol_boost = args[2];
        }

        try {
            /* Recreate dir structure in output dir */
            mkDirsIfNotEx();
                    
            /* Find and process each file of a supported type */
            Files.find(
                CWD,
                999,
                (p, bfa) -> p.toString().matches(".*\\.(?:flac|dsf|dff|wav|aiff|m4a|mp3)$")
            ).forEach( p -> conv(p) );
        }
        catch (Throwable t) {
            t.printStackTrace();
        }
        finally {
            /* Exit with proper status */
            if (error) {
                System.exit(-1);
            }
            else {
                System.exit(0);
            }
        }
    }
}
