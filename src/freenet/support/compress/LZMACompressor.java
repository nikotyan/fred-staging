/* This code is part of Freenet. It is distributed under the GNU General
* Public License, version 2 (or at your option any later version). See
* http://www.gnu.org/ for further details of the GPL. */
package freenet.support.compress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import SevenZip.Compression.LZMA.Decoder;
import SevenZip.Compression.LZMA.Encoder;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.CountedInputStream;
import freenet.support.io.CountedOutputStream;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
public class LZMACompressor implements Compressor {

	// Copied from EncoderThread. See below re licensing.
	public Bucket compress(Bucket data, BucketFactory bf, long maxReadLength, long maxWriteLength) throws IOException, CompressionOutputSizeException {
		Bucket output;
		InputStream is = null;
		OutputStream os = null;
		try {
			output = bf.makeBucket(maxWriteLength);
			is = data.getInputStream();
			os = output.getOutputStream();
			if(Logger.shouldLog(LogLevel.MINOR, this))
				Logger.minor(this, "Compressing "+data+" size "+data.size()+" to new bucket "+output);
			compress(is, os, maxReadLength, maxWriteLength);
			is.close();
			os.close();
		} finally {
			Closer.close(is);
			Closer.close(os);
		}
		return output;
	}
	
	public long compress(InputStream is, OutputStream os, long maxReadLength, long maxWriteLength) throws IOException {
		CountedInputStream cis = null;
		CountedOutputStream cos = null;
		cis = new CountedInputStream(new BufferedInputStream(is));
		cos = new CountedOutputStream(new BufferedOutputStream(os));
		Encoder encoder = new Encoder();
        encoder.SetEndMarkerMode( true );
        // Dictionary size 1MB, this is equivalent to lzma -4, it uses 16MB to compress and 2MB to decompress.
        // Next one up is 2MB = -5 = 26M compress, 3M decompress.
        encoder.SetDictionarySize( 1 << 20 );
        // enc.WriteCoderProperties( out );
        // 5d 00 00 10 00
        encoder.Code( cis, cos, -1, -1, null );
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Read "+cis.count()+" written "+cos.written());
		return cos.written();
	}

	public Bucket decompress(Bucket data, BucketFactory bf, long maxLength, long maxCheckSizeLength, Bucket preferred) throws IOException, CompressionOutputSizeException {
		Bucket output;
		if(preferred != null)
			output = preferred;
		else
			output = bf.makeBucket(maxLength);
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Decompressing "+data+" size "+data.size()+" to new bucket "+output);
		CountedInputStream is = new CountedInputStream(new BufferedInputStream(data.getInputStream()));
		CountedOutputStream os = new CountedOutputStream(new BufferedOutputStream(output.getOutputStream()));
		decompress(is, os, maxLength, maxCheckSizeLength);
		os.close();
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Output: "+output+" size "+output.size()+" read "+is.count()+" written "+os.written());
		return output;
	}

	
	// Copied from DecoderThread
	// LICENSING: DecoderThread is LGPL 2.1/CPL according to comments.
	
    static final int propSize = 5;
    
    static final byte[] props = new byte[propSize];

    static {
        // enc.SetEndMarkerMode( true );
        // enc.SetDictionarySize( 1 << 20 );
        props[0] = 0x5d;
        props[1] = 0x00;
        props[2] = 0x00;
        props[3] = 0x10;
        props[4] = 0x00;
    }

	private void decompress(InputStream is, OutputStream os, long maxLength, long maxCheckSizeBytes) throws IOException, CompressionOutputSizeException {
		Decoder decoder = new Decoder();
		decoder.SetDecoderProperties(props);
		decoder.Code(is, os, maxLength);
	}

	public int decompress(byte[] dbuf, int i, int j, byte[] output) throws CompressionOutputSizeException {
		// Didn't work with Inflater.
		// FIXME fix sometimes to use Inflater - format issue?
		ByteArrayInputStream bais = new ByteArrayInputStream(dbuf, i, j);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(output.length);
		int bytes = 0;
		try {
			decompress(bais, baos, output.length, -1);
			bytes = baos.size();
		} catch (IOException e) {
			// Impossible
			throw new Error("Got IOException: " + e.getMessage(), e);
		}
		byte[] buf = baos.toByteArray();
		System.arraycopy(buf, 0, output, 0, bytes);
		return bytes;
	}
}
