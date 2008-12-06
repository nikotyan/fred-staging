package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.client.InsertException;
import freenet.keys.NodeCHK;
import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.compress.CompressJob;
import freenet.support.compress.CompressionOutputSizeException;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.BucketChainBucketFactory;
import freenet.support.io.NativeThread;

/**
 * Compress a file in order to insert it. This class acts as a tag in the database to ensure that inserts
 * are not forgotten about, and also can be run on a non-database thread from an executor.
 * 
 * FIXME how many compressors do we want to have running simultaneously? Probably we should have a compression
 * queue, or at least a SerialExecutor?
 * 
 * @author toad
 */
public class InsertCompressor implements CompressJob {
	
	/** Database handle to identify which node it belongs to in the database */
	public final long nodeDBHandle;
	/** The SingleFileInserter we report to. We were created by it and when we have compressed our data we will
	 * call a method to process it and schedule the data. */
	public final SingleFileInserter inserter;
	/** The original data */
	final Bucket origData;
	/** If we can get it into one block, don't compress any further */
	public final int minSize;
	/** BucketFactory */
	public final BucketFactory bucketFactory;
	public final boolean persistent;
	private transient boolean scheduled;
	private static boolean logMINOR;
	
	public InsertCompressor(long nodeDBHandle2, SingleFileInserter inserter2, Bucket origData2, int minSize2, BucketFactory bf, boolean persistent) {
		this.nodeDBHandle = nodeDBHandle2;
		this.inserter = inserter2;
		this.origData = origData2;
		this.minSize = minSize2;
		this.bucketFactory = bf;
		this.persistent = persistent;
	}

	public void init(ObjectContainer container, final ClientContext ctx) {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(persistent) {
			container.activate(inserter, 1);
			container.activate(origData, 1);
		}
		if(origData == null) {
			if(inserter == null || inserter.cancelled()) {
				container.delete(this);
				return; // Inserter was cancelled, we weren't told.
			} else if(inserter.started()) {
				Logger.error(this, "Inserter started already, but we are about to attempt to compress the data!");
				container.delete(this);
				return; // Already started, no point ... but this really shouldn't happen.
			} else {
				Logger.error(this, "Original data was deleted but inserter neither deleted nor cancelled nor missing!");
				container.delete(this);
				return;
			}
		}
		synchronized(this) {
			// Can happen with the above activation and lazy query evaluation.
			if(scheduled) {
				Logger.error(this, "Already scheduled compression, not rescheduling");
				return;
			}
			scheduled = true;
		}
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Compressing "+this+" : origData.size="+origData.size()+" for "+inserter);
		ctx.rc.enqueueNewJob(this);
	}

	public void tryCompress(final ClientContext context) throws InsertException {
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long origSize = origData.size();
		COMPRESSOR_TYPE bestCodec = null;
		Bucket bestCompressedData = origData;
		long bestCompressedDataSize = origSize;
		
		if(logMINOR) Logger.minor(this, "Attempt to compress the data");
		// Try to compress the data.
		// Try each algorithm, starting with the fastest and weakest.
		// Stop when run out of algorithms, or the compressed data fits in a single block.
		try {
			for(final COMPRESSOR_TYPE comp : COMPRESSOR_TYPE.values()) {
				boolean shouldFreeOnFinally = true;
				Bucket result = null;
				try {
				if(logMINOR)
					Logger.minor(this, "Attempt to compress using " + comp);
				// Only produce if we are compressing *the original data*
				final int phase = comp.metadataID;
				if(persistent) {
					context.jobRunner.queue(new DBJob() {

						public void run(ObjectContainer container, ClientContext context) {
							if(container.ext().isActive(inserter))
								Logger.error(this, "ALREADY ACTIVE in start compression callback: "+inserter);
							container.activate(inserter, 1);
							inserter.onStartCompression(comp, container, context);
							container.deactivate(inserter, 1);
						}
						
					}, NativeThread.NORM_PRIORITY+1, false);
				} else {
					try {
						inserter.onStartCompression(comp, null, context);
					} catch (Throwable t) {
						Logger.error(this, "Transient insert callback threw "+t, t);
					}
				}
				
				result = comp.compress(origData, new BucketChainBucketFactory(bucketFactory, NodeCHK.BLOCK_SIZE, persistent ? context.jobRunner : null, 1024), origSize, bestCompressedDataSize);
				long resultSize = result.size();
				if(resultSize < minSize) {
					bestCodec = comp;
					if(bestCompressedData != null)
						bestCompressedData.free();
					bestCompressedData = result;
					bestCompressedDataSize = resultSize;
					shouldFreeOnFinally = false;
					break;
				}
				if(resultSize < bestCompressedDataSize) {
					if(bestCompressedData != null)
						bestCompressedData.free();
					bestCompressedData = result;
					bestCompressedDataSize = resultSize;
					bestCodec = comp;
				}
				} catch(CompressionOutputSizeException e) {
					continue;       // try next compressor type
				} finally {
					if(shouldFreeOnFinally && (result != null))
						result.free();
				}
			}
			
			final CompressionOutput output = new CompressionOutput(bestCompressedData, bestCodec);
			
			if(persistent) {
			
				context.jobRunner.queue(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(inserter))
							Logger.error(this, "ALREADY ACTIVE in compressed callback: "+inserter);
						container.activate(inserter, 1);
						inserter.onCompressed(output, container, context);
						container.deactivate(inserter, 1);
						container.delete(InsertCompressor.this);
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				// We do it off thread so that RealCompressor can release the semaphore
				context.mainExecutor.execute(new PrioRunnable() {

					public int getPriority() {
						return NativeThread.NORM_PRIORITY;
					}

					public void run() {
						try {
							inserter.onCompressed(output, null, context);
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" running compression job", t);
							context.jobRunner.queue(new DBJob() {

								public void run(ObjectContainer container, ClientContext context) {
									container.delete(InsertCompressor.this);
								}
								
							}, NativeThread.NORM_PRIORITY+1, false);
						}
					}
					
				}, "Insert thread for "+this);
			}
			
		} catch (final IOException e) {
			if(persistent) {
				context.jobRunner.queue(new DBJob() {
					
					public void run(ObjectContainer container, ClientContext context) {
						if(container.ext().isActive(inserter))
							Logger.error(this, "ALREADY ACTIVE in compress failure callback: "+inserter);
						container.activate(inserter, 1);
						container.activate(inserter.cb, 1);
						inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, container, context);
						container.deactivate(inserter.cb, 1);
						container.deactivate(inserter, 1);
						container.delete(InsertCompressor.this);
					}
					
				}, NativeThread.NORM_PRIORITY+1, false);
			} else {
				inserter.cb.onFailure(new InsertException(InsertException.BUCKET_ERROR, e, null), inserter, null, context);
			}
			
		}
	}

	/**
	 * Create an InsertCompressor, add it to the database, schedule it.
	 * @param container
	 * @param context
	 * @param inserter2
	 * @param origData2
	 * @param oneBlockCompressedSize
	 * @param bf
	 * @return
	 */
	public static InsertCompressor start(ObjectContainer container, ClientContext context, SingleFileInserter inserter, 
			Bucket origData, int minSize, BucketFactory bf, boolean persistent) {
		InsertCompressor compressor = new InsertCompressor(context.nodeDBHandle, inserter, origData, minSize, bf, persistent);
		if(persistent)
			container.store(compressor);
		compressor.init(container, context);
		return compressor;
	}

	public static void load(ObjectContainer container, ClientContext context) {
		final long handle = context.nodeDBHandle;
		Query query = container.query();
		query.constrain(InsertCompressor.class);
		query.descend("nodeDBHandle").constrain(handle);
		ObjectSet<InsertCompressor> results = query.execute();
		while(results.hasNext()) {
			InsertCompressor comp = results.next();
			if(!container.ext().isActive(comp)) {
				Logger.error(InsertCompressor.class, "InsertCompressor not activated by query?!?!");
				container.activate(comp, 1);
			}
			comp.init(container, context);
		}
	}

	public void onFailure(final InsertException e, ClientPutState c, ClientContext context) {
		if(persistent) {
			context.jobRunner.queue(new DBJob() {
				
				public void run(ObjectContainer container, ClientContext context) {
					if(container.ext().isActive(inserter))
						Logger.error(this, "ALREADY ACTIVE in compress failure callback: "+inserter);
					container.activate(inserter, 1);
					container.activate(inserter.cb, 1);
					inserter.cb.onFailure(e, inserter, container, context);
					container.deactivate(inserter.cb, 1);
					container.deactivate(inserter, 1);
					container.delete(InsertCompressor.this);
				}
				
			}, NativeThread.NORM_PRIORITY+1, false);
		} else {
			inserter.cb.onFailure(e, inserter, null, context);
		}
		
	}

}

class CompressionOutput {
	public CompressionOutput(Bucket bestCompressedData, COMPRESSOR_TYPE bestCodec2) {
		this.data = bestCompressedData;
		this.bestCodec = bestCodec2;
	}
	final Bucket data;
	final COMPRESSOR_TYPE bestCodec;
}