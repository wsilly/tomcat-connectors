/*
 * ====================================================================
 *
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 1999 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowlegement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "The Jakarta Project", "Tomcat", and "Apache Software
 *    Foundation" must not be used to endorse or promote products derived
 *    from this software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache"
 *    nor may "Apache" appear in their names without prior written
 *    permission of the Apache Group.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 * [Additional notices, if required by prior licensing conditions]
 *
 */

package org.apache.jk.common;

import java.io.*;

import java.net.*;
import java.util.*;

import org.apache.tomcat.util.buf.*;
import org.apache.tomcat.util.http.*;

import org.apache.tomcat.util.threads.*;

import org.apache.jk.core.*;
import org.apache.jk.apr.*;


/** Pass messages using unix domain sockets.
 *
 * @author Costin Manolache
 */
public class ChannelUn extends JkHandler {

    String file;
    ThreadPool tp;
    String jkHome;
    String aprHome;

    /* ==================== Tcp socket options ==================== */

    public ThreadPool getThreadPool() {
        return tp;
    }
    
    public void setFile( String f ) {
        file=f;
    }

    /** Set the base dir of the jk webapp. This is used to locate
     *  the (fixed) path to the native lib.
     */
    public void setJkHome( String s ) {
        jkHome=s;
    }

    /** Directory where APR and jni_connect are installed.
     */
    public void setAprHome( String s ) {
        aprHome=s;
    }

    /* ==================== ==================== */
    long unixListenSocket;
    int socketNote=1;
    int isNote=2;
    int osNote=3;
    AprImpl apr;
    long gPool;
    
    public void accept( MsgContext ep ) throws IOException {
        long l= apr.unAccept(gPool, unixListenSocket);
        /* We could create a real java.net.Socket, or a UnixSocket, etc
         */
        ep.setNote( socketNote, new Long( l ) );

        if( log.isDebugEnabled() )
            log.debug("Accepted socket " + l );
    }

    public void init() throws IOException {
        apr=(AprImpl)wEnv.getHandler("apr");
        if( apr==null || ! apr.isLoaded() ) {
            log.error("Apr is not available, disabling unix channel ");
            apr=null;
            return;
        }
        if( file==null ) {
            log.error("No file, disabling unix channel");
            return;
        }
        if( wEnv.getLocalId() != 0 ) {
            file=file+ wEnv.getLocalId();
        }
        
        if( next==null ) {
            if( nextName!=null ) 
                setNext( wEnv.getHandler( nextName ) );
            if( next==null )
                next=wEnv.getHandler( "dispatch" );
            if( next==null )
                next=wEnv.getHandler( "request" );
        }

        tp=new ThreadPool();
        if( log.isDebugEnabled() ) log.debug( "Creating pool " + gPool );
        gPool=apr.poolCreate( 0 );

        File socketFile=new File( file );
        if( socketFile.exists() ) {
            // The socket file cannot be removed ...
            if (!socketFile.delete())
                  throw(new IOException("Cannot remove " + file));
        }
        unixListenSocket=apr.unSocketListen( gPool, file, 10 );
        if (unixListenSocket<0)
            throw(new IOException("Cannot create listening socket " + file));

        log.info("Listening on unix socket: " + file );
        
        // Run a thread that will accept connections.
        tp.start();
        AprAcceptor acceptAjp=new AprAcceptor(  this );
        tp.runIt( acceptAjp);
    }

    public void open(MsgContext ep) throws IOException {
    }

    
    public void close(MsgContext ep) throws IOException {
        if( apr==null ) return;
        Long s=(Long)ep.getNote( socketNote );
        apr.unSocketClose(gPool, s.longValue(),3);
    }

    public void destroy() throws IOException {
        if( apr==null ) return;
        try {
            if( tp != null )
                tp.shutdown();
            
            if(apr !=null ) 
                apr.unSocketClose(gPool, unixListenSocket,3);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    public int send( Msg msg, MsgContext ep)
        throws IOException
    {
        msg.end(); // Write the packet header
        byte buf[]=msg.getBuffer();
        int len=msg.getLen();
        
        if(log.isDebugEnabled() )
            log.debug("send() " + len + " " + buf[4] );

        Long s=(Long)ep.getNote( socketNote );

        apr.unWrite( gPool, s.longValue(), buf, 0, len );
        return len;
    }

    public int receive( Msg msg, MsgContext ep )
        throws IOException
    {
        if (log.isDebugEnabled()) {
            log.debug("receive()");
        }

        byte buf[]=msg.getBuffer();
        int hlen=msg.getHeaderLength();
        
	// XXX If the length in the packet header doesn't agree with the
	// actual number of bytes read, it should probably return an error
	// value.  Also, callers of this method never use the length
	// returned -- should probably return true/false instead.

        int rd = this.read(ep, buf, 0, hlen );
        
        if(rd < 0) {
            // Most likely normal apache restart.
            return rd;
        }

        msg.processHeader();

        /* After processing the header we know the body
           length
        */
        int blen=msg.getLen();
        
	// XXX check if enough space - it's assert()-ed !!!
        
 	int total_read = 0;
        
        total_read = this.read(ep, buf, hlen, blen);
        
        if (total_read <= 0) {
            log.warn("can't read body, waited #" + blen);
            return  -1;
        }
        
        if (total_read != blen) {
             log.warn( "incomplete read, waited #" + blen +
                        " got only " + total_read);
            return -2;
        }
        
        if (log.isDebugEnabled())
             log.debug("receive:  total read = " + total_read);
	return total_read;
    }
    
    /**
     * Read N bytes from the InputStream, and ensure we got them all
     * Under heavy load we could experience many fragmented packets
     * just read Unix Network Programming to recall that a call to
     * read didn't ensure you got all the data you want
     *
     * from read() Linux manual
     *
     * On success, the number of bytes read is returned (zero indicates end of file),
     * and the file position is advanced by this number.
     * It is not an error if this number is smaller than the number of bytes requested;
     * this may happen for example because fewer bytes
     * are actually available right now (maybe because we were close to end-of-file,
     * or because we are reading from a pipe, or  from  a
     * terminal),  or  because  read()  was interrupted by a signal.
     * On error, -1 is returned, and errno is set appropriately. In this
     * case it is left unspecified whether the file position (if any) changes.
     *
     **/
    public int read( MsgContext ep, byte[] b, int offset, int len) throws IOException {
        Long s=(Long)ep.getNote( socketNote );
        int pos = 0;
        int got;

        while(pos < len) {
            got=apr.unRead( gPool, s.longValue(),
                            b, pos + offset, len - pos);

            if (log.isDebugEnabled()) {
                log.debug("reading  # " + b + " " + (b==null ? 0: b.length) + " " +
                  offset + " " + len + " got # " + got);
            }
            // connection just closed by remote. 
            if (got <= 0) {
                return got;
            }

            pos += got;
        }
        return pos;
    }

    
//     public MsgContext createEndpoint() {
//         MsgContext mc=new MsgContext();
//         mc.setChannel( this );
//         mc.setWorkerEnv( wEnv );
//         return mc;
//     }

    boolean running=true;
    
    /** Accept incoming connections, dispatch to the thread pool
     */
    void acceptConnections() {
        if( log.isDebugEnabled() )
            log.debug("Accepting ajp connections on " + file);
        if( apr==null ) return;
        while( running ) {
            try {
                MsgContext ep=new MsgContext();
                ep.setSource( this );
                ep.setWorkerEnv( wEnv );
                this.accept(ep);
                AprConnection ajpConn=
                    new AprConnection(this, ep);
                tp.runIt( ajpConn );
            } catch( Exception ex ) {
                ex.printStackTrace();
            }
        }
    }

    /** Process a single ajp connection.
     */
    void processConnection(MsgContext ep) {
        if( log.isDebugEnabled() )
            log.debug( "New ajp connection ");
        try {
            MsgAjp recv=new MsgAjp();
            while( running ) {
                int res=this.receive( recv, ep );
                if( res<0 ) {
                    // EOS
                    break;
                }
                ep.setType(0);
                int status=this.invoke( recv, ep );
            }
            this.close( ep );
        } catch( Exception ex ) {
            ex.printStackTrace();
        }
    }

    public int invoke( Msg msg, MsgContext ep ) throws IOException {
        int type=ep.getType();

        switch( type ) {
        case JkHandler.HANDLE_RECEIVE_PACKET:
            return receive( msg, ep );
        case JkHandler.HANDLE_SEND_PACKET:
            return send( msg, ep );
        }

        return next.invoke( msg, ep );
    }

    private static org.apache.commons.logging.Log log=
        org.apache.commons.logging.LogFactory.getLog( ChannelUn.class );
}

class AprAcceptor implements ThreadPoolRunnable {
    ChannelUn wajp;
    
    AprAcceptor(ChannelUn wajp ) {
        this.wajp=wajp;
    }

    public Object[] getInitData() {
        return null;
    }

    public void runIt(Object thD[]) {
        wajp.acceptConnections();
    }
}

class AprConnection implements ThreadPoolRunnable {
    ChannelUn wajp;
    MsgContext ep;

    AprConnection(ChannelUn wajp, MsgContext ep) {
        this.wajp=wajp;
        this.ep=ep;
    }


    public Object[] getInitData() {
        return null;
    }
    
    public void runIt(Object perTh[]) {
        wajp.processConnection(ep);
    }
}
