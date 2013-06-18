/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package druid.examples.perf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.metamx.common.exception.FormattedException;
import com.metamx.common.logger.Logger;
import com.metamx.druid.indexer.data.StringInputRowParser;
import com.metamx.druid.input.InputRow;
import com.metamx.druid.realtime.firehose.Firehose;
import com.metamx.druid.realtime.firehose.FirehoseFactory;

import io.d8a.conjure.Conjurer;
import io.d8a.conjure.Printer;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 */
@JsonTypeName("conjurer")
public class ConjurerFirehoseFactory implements FirehoseFactory
{
  private static final Logger log = new Logger(ConjurerFirehoseFactory.class);
  private final AtomicLong count = new AtomicLong();

  @JsonProperty
  private final StringInputRowParser parser;

  @JsonProperty
  private final int rate;

  @JsonProperty
  private final String file;

  private final ExecutorService pool;
  private final BlockingQueue<InputRow> queue;

  @JsonCreator
  public ConjurerFirehoseFactory(
      @JsonProperty("parser") StringInputRowParser parser,
      @JsonProperty("rate") int rate,
      @JsonProperty("file") String file
  )
  {
    this.parser = parser;
    this.pool = Executors.newFixedThreadPool(2);
    this.queue = new LinkedBlockingQueue<InputRow>();
    this.rate = rate;
    this.file = file;
  }
  
  private void initThreads(){
	  pool.submit(new TpsLogger());
	  pool.submit(new Runnable() {
		
		@Override
		public void run() {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			Conjurer conj = new Conjurer(Long.MAX_VALUE, new ConjurerFirehosePrinter(), rate, file);
			conj.exhaust();
		}
	  });
  }

  @Override
  public Firehose connect() throws IOException
  {
	initThreads();
    return new Firehose()
    {
      @Override
      public boolean hasMore()
      {
        return Boolean.TRUE;
      }


      @Override
      public InputRow nextRow() throws FormattedException
      {
    	  try {
    		count.incrementAndGet();
			return queue.take();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
      }

      @Override
      public Runnable commit()
      {
        return new Runnable()
        {
          @Override
          public void run()
          {
            log.info("committing offsets");
          }
        };
      }

      @Override
      public void close() throws IOException
      {
        log.info("closing down perf tests.");
        pool.shutdownNow();
      }
      
    };
  }
  
  private final class TpsLogger implements Runnable {
	  
	private final long INTERVAL = 4;
	private long prevCount = 0;
	private long loggedCount;

	@Override
	public void run() {
		while (true){
				try {
					TimeUnit.SECONDS.sleep(INTERVAL);
				} catch (InterruptedException e) {
					log.info("Exiting tps logger thread...");
					return;
				}
				loggedCount++;
				
				long curr = count.get();
				long periodic = curr - prevCount;
				log.info("=========== Time: %4d secs ===============", loggedCount * INTERVAL);
				log.info("Periodic  : Count : %8d , Tps : %6d", periodic, periodic / INTERVAL);
				log.info("Cumulative: Count : %8d , Tps : %6d , Size: %d", curr, curr / ( loggedCount * INTERVAL), queue.size());
				log.info("===========================================");
				prevCount = curr;
		}
	}
	  
  }
  
  private final class ConjurerFirehosePrinter implements Printer {

		@Override
		public void print(String message) {
			try{
//				log.info(message);
				queue.add(parser.parse(message));
			} catch (Exception e) {
				log.error(e.getMessage());
			}
		}

	}

  
}
