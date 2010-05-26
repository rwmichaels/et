/*----------------------------------------------------------------------------*
 *  Copyright (c) 2001        Southeastern Universities Research Association, *
 *                            Thomas Jefferson National Accelerator Facility  *
 *                                                                            *
 *    This software was developed under a United States Government license    *
 *    described in the NOTICE file included as part of this distribution.     *
 *                                                                            *
 *    Author:  Carl Timmer                                                    *
 *             timmer@jlab.org                   Jefferson Lab, MS-12H        *
 *             Phone: (757) 269-5130             12000 Jefferson Ave.         *
 *             Fax:   (757) 269-5800             Newport News, VA 23606       *
 *                                                                            *
 *----------------------------------------------------------------------------*/

package org.jlab.coda.et.system;

import java.lang.*;
import java.util.*;
import java.util.Map.*;
import java.io.*;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jlab.coda.et.exception.*;
import org.jlab.coda.et.*;
import org.jlab.coda.et.enums.Modify;
import org.jlab.coda.et.enums.Priority;
import org.jlab.coda.et.enums.DataStatus;

/**
 * This class implements a thread which listens for users trying to connect to
 * the ET system. It starts another thread for each tcp socket connection
 * established to a user of the system.
 *
 * @author Carl Timmer
 */

class SystemTcpServer extends Thread {

    /** Port number to listen on. */
    private int port;

    /** Et system object. */
    private SystemCreate sys;


    /** Createes a new SystemTcpServer object.
     *  @param sys ET system object */
    SystemTcpServer(SystemCreate sys) {
        this.sys = sys;
        port = sys.getConfig().getServerPort();
    }


    /** Start thread to listen for connections and spawn off communication
     *  handling threads. */
    public void run() {
        if (sys.getConfig().getDebug() >= Constants.debugInfo) {
            System.out.println("Running TCP Server Thread");
        }

        // use the default port number since one wasn't specified
        if (port < 1) {
            port = Constants.serverPort;
        }

        // let exceptions propagate up a level

        // open a listening socket
        try {

            // Direct buffer for reading 3 magic ints with nonblocking IO
            int BYTES_TO_READ = 12;
            ByteBuffer buffer = ByteBuffer.allocateDirect(BYTES_TO_READ);

            // Create channel and bind to port. If that isn't possible, exit.
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.socket().setReuseAddress(true);
            serverChannel.socket().bind(new InetSocketAddress(port));
            serverChannel.socket().setSoTimeout(2000);

            while (true) {
                // socket to client created
                SocketChannel channel;
                Socket sock;
                while (true) {
                    try {
                        // accept the connection from the client
                        channel = serverChannel.accept();
                        sock = channel.socket();
                        break;
                    }
                    // server socket accept timeout
                    catch (InterruptedIOException ex) {
                        // check to see if we've been commanded to die
                        if (sys.killAllThreads()) {
                            return;
                        }
                    }
                }
                // Set reading timeout to 1/2 second so dead clients
                // can be found by reading on a socket.
                sock.setSoTimeout(500);
                // Set tcpNoDelay so no packets are delayed
                sock.setTcpNoDelay(true);
                // set buffer size
                sock.setReceiveBufferSize(65535);
                sock.setSendBufferSize(65535);

                // Check to see if this is a legitimate client or some imposter.
                // Don't want to block on read here since it may not be a real client
                // and may block forever - tying up the server.
                int bytes, bytesRead=0, loops=0;
                buffer.clear();
                buffer.limit(BYTES_TO_READ);
                channel.configureBlocking(false);

                // read magic numbers
                while (bytesRead < BYTES_TO_READ) {
//System.out.println("  try reading rest of Buffer");
//System.out.println("  Buffer capacity = " + buffer.capacity() + ", limit = " + buffer.limit()
//                    + ", position = " + buffer.position() );
                    bytes = channel.read(buffer);
                    // for End-of-stream ...
                    if (bytes == -1) {
                        channel.close();
                        continue;
                    }
                    bytesRead += bytes;
//System.out.println("  bytes read = " + bytesRead);

                    // if we've read everything, look to see if it's sent the magic #s
                    if (bytesRead >= BYTES_TO_READ) {
                        buffer.flip();
                        int magic1 = buffer.getInt();
                        int magic2 = buffer.getInt();
                        int magic3 = buffer.getInt();
                        if (magic1 != Constants.magicNumbers[0] ||
                                magic2 != Constants.magicNumbers[1] ||
                                magic3 != Constants.magicNumbers[2])  {
//System.out.println("SystemTcpServer:  Magic numbers did NOT match");
                            channel.close();
                        }
                    }
                    else {
                        // give client 10 loops (.1 sec) to send its stuff, else no deal
                        if (++loops > 10) {
//System.out.println("SystemTcpServer:  Client taking too long to send 3 ints, terminate connection");
                            channel.close();
                            continue;
                        }
                        try { Thread.sleep(10); }
                        catch (InterruptedException e) { }
                    }
                }

                // change back to blocking socket
                channel.configureBlocking(true);

                // create thread to deal with client
                ClientThread connection = new ClientThread(sys, channel.socket());
                connection.start();
            }

        }
        catch (SocketException ex) {
        }
        catch (IOException ex) {
        }
        return;
    }

}


/**
 * This class handles all communication between an ET system and a user who has
 * opened that ET system.
 *
 * @author Carl Timmer
 */

class ClientThread extends Thread {

    /** Tcp socket. */
    private Socket sock;

    /** ET system object. */
    private SystemCreate sys;

    /** ET system configuration object. */
    private SystemConfig config;

    /** Data input stream built on top of the socket's input stream (with an
     *  intervening buffered input stream). */
    private DataInputStream  in;

    /** Data output stream built on top of the socket's output stream (with an
     *  intervening buffered output stream). */
    private DataOutputStream out;

    /** Client is 64 bits? */
    boolean bit64;


    /**
     *  Create a new ClientThread object.
     *  @param sys ET system object.
     *  @param sock TCP socket.
     */
    ClientThread(SystemCreate sys, Socket sock) {
        this.sys  = sys;
        this.sock = sock;
        config = sys.getConfig();
    }


    /** Start thread to handle communications with user. */
    public void run() {

        try {
            // buffered communication streams for efficiency
            in  = new DataInputStream(new  BufferedInputStream(sock.getInputStream(),   65535));
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 65535));

            int endian = in.readInt();
            int length = in.readInt();
            int b64    = in.readInt();
            bit64      = b64 == 1;
            in.readLong();

            byte[] buf = new byte[length];
            in.readFully(buf, 0, length);
            String etName = new String(buf, 0, length - 1, "ASCII");

            // see if the ET system that the client is
            // trying to connect to is this one.
            if (!etName.equals(sys.getName())) {
                if (config.getDebug() >= Constants.debugError) {
                    System.out.println("Tcp Server: client trying to connect to " + etName);
                }
                // send error to client
                out.writeInt(Constants.error);
                out.flush();
                return;
            }

            // send ET system info back to client
            out.writeInt(Constants.ok);
            out.writeInt(Constants.endianBig);
            out.writeInt(config.getNumEvents());
            out.writeLong(config.getEventSize());
            out.writeInt(Constants.version);
            out.writeInt(Constants.stationSelectInts);
            out.writeInt(Constants.langJava);
            out.writeInt(Constants.bit64);
            out.writeInt(0);
            out.flush();

            /* wait for and process client requests */
            commandLoop();

            return;
        }
        catch (IOException ex) {
            if (config.getDebug() >= Constants.debugError) {
                System.out.println("Tcp Server: IO error in client etOpen");
            }
        }
        finally {
            // we are done with the socket
            try {
                sock.close();
            }
            catch (IOException ex) {
            }
        }
    }


    /**  Wait for and implement commands from the user. */
    private void commandLoop() {

        // Keep track of all the attachments this client makes
        // as they may need to be detached if the client dies
        // without cleanly disconnecting itself. Detaching
        // takes care of all events that were sent to clients
        // as events to be modified, but were never put back.

        // for efficiency, keep local copy of constants
        final int selectInts   = Constants.stationSelectInts;
        final int dataShift    = Constants.dataShift;
        final int priorityMask = Constants.priorityMask;
        final int dataMask     = Constants.dataMask;
        final int modify       = Constants.modify;
        final int ok           = Constants.ok;

        int command;
        EventImpl[] evs = null;
        HashMap<Integer, AttachmentLocal> attachments =
                new HashMap<Integer, AttachmentLocal>(sys.getConfig().getAttachmentsMax() + 1);
        // buffer for sending events to users
        byte[] buffer = new byte[65535];
        // buffer for reading command parameters (6 ints worth)
        byte[] params = new byte[32 + 4 * selectInts];

        // The Command Loop ...
        try {
            while (true) {
                // First, read the remote command. Remember, the
                // socket has a read timeout of 1/2 second.
                while (true) {
                    try {
                        command = in.readInt();
                        break;
                    }
                    // socket read timeout
                    catch (InterruptedIOException ex) {
                        // check to see if we've been commanded to die
                        if (sys.killAllThreads()) {
                            return;
                        }
                    }
                }

                // Since there are so many commands, break up things up a bit,
                // start off with commands for local clients for use in Linux
                // or other non-mutex sharing operating systems.

                if (command < Constants.netEvGet) {
                    // No local Linux stuff in Java implementation
                    if (config.getDebug() >= Constants.debugError) {
                        System.out.println("No Java support for local Linux");
                    }
                    throw new EtReadException("No Java support for local Linux");
                }

                else if (command < Constants.netAlive) {

                    switch (command) {

                        case Constants.netEvGet: {
                            in.readFully(params, 0, 20);
                            int err = ok;
                            int attId = Utils.bytesToInt(params, 0);
                            int mode  = Utils.bytesToInt(params, 4);
                            int mod   = Utils.bytesToInt(params, 8);
                            int sec   = Utils.bytesToInt(params, 12);
                            int nsec  = Utils.bytesToInt(params, 16);
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            try {
                                if (mode == Constants.timed) {
                                    int uSec = sec * 1000000 + nsec / 1000;
                                    evs = sys.getEvents(att, mode, uSec, 1);
                                }
                                else if (mode == Constants.sleep) {
                                    // There's a problem if we have a remote client that is waiting
                                    // for another event by sleeping and the events stop flowing. In
                                    // that case, the client can be killed and the ET system does NOT
                                    // know about it. Since this thread will be stuck in "getEvents",
                                    // it will not immediately detect the break in the socket - at least
                                    // not until events start flowing again. To circumvent this, implement
                                    // "sleep" by repeats of "timed" every few seconds to allow
                                    // detection of broken socket between calls to "getEvents".

                                    // Store the fact we're trying to sleep - necessary when
                                    // told to wake up.
                                    att.setSleepMode(true);

                                    tryToGetEvents:
                                    while (true) {
                                        // try a 4 second wait for an event
                                        try {
                                            if (att.isWakeUp()) {
                                                att.setWakeUp(false);
                                                throw new EtWakeUpException("attachment " + att.getId() + " woken up");
                                            }
                                            evs = sys.getEvents(att, Constants.timed, 4000000, 1);
                                            // no longer in sleep mode
                                            att.setSleepMode(false);
                                            // may have been told to wake up between last 2 statements.
                                            att.setWakeUp(false);
                                            break;
                                        }
                                        // if timeout, check socket to see if still open
                                        catch (EtTimeoutException tx) {
                                            try {
                                                // 1/2 second max delay on read
                                                in.readInt();
                                                // should never be able to get here
                                                att.setSleepMode(false);
                                                throw new EtException("communication protocol error");
                                            }
                                            // if there's an interrupted ex, socket is OK
                                            catch (InterruptedIOException ex) { }
                                        }
                                    }

                                }
                                else {
                                    evs = sys.getEvents(att, mode, 0, 1);
                                }

                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtBusyException ex) {
                                err = Constants.errorBusy;
                            }
                            catch (EtEmptyException ex) {
                                err = Constants.errorEmpty;
                            }
                            catch (EtWakeUpException ex) {
                                err = Constants.errorWakeUp;
                                att.setSleepMode(false);
                            }
                            catch (EtTimeoutException ex) {
                                err = Constants.errorTimeout;
                            }

                            if (err != ok) {
                                out.writeInt(err);
                                out.flush();
                                break;
                            }

                            EventImpl ev = evs[0];

                            // handle buffering by hand
                            byte[] buf = new byte[4 * (10 + selectInts) + ev.getLength()];

                            // first send error
                            Utils.intToBytes(err, buf, 0);
                            Utils.longToBytes((long)ev.getLength(),  buf,  4);
                            Utils.longToBytes((long)ev.getMemSize(), buf, 12);
                            Utils.intToBytes(ev.getPriority().getValue() |
                                             ev.getDataStatus().getValue() << dataShift, buf, 20);
                            Utils.intToBytes(ev.getId(), buf, 24);  // skip 4 bytes here
                            Utils.intToBytes(ev.getByteOrder() == ByteOrder.LITTLE_ENDIAN ?
                                             Constants.endianLittle : Constants.endianBig,
                                             buf, 32);
                            // arrays are initialized to zero so skip 0 values elements
                            int index = 36;
                            int[] control = ev.getControl();
                            for (int i = 0; i < selectInts; i++) {
                                Utils.intToBytes(control[i], buf, index += 4);
                            }
                            System.arraycopy(ev.getData(), 0, buf, index += 4, ev.getLength());

                            out.write(buf);
                            out.flush();

                            ev.setModify(Modify.getModify(mod));
                            if (mod == 0) {
                                sys.putEvents(att, evs);
                            }
                            evs = null;
                        }
                        break;


                        case Constants.netEvsGet: {
                            in.readFully(params, 0, 24);
                            int err = ok;
                            int attId = Utils.bytesToInt(params,  0);
                            int mode  = Utils.bytesToInt(params,  4);
                            int mod   = Utils.bytesToInt(params,  8);
                            int count = Utils.bytesToInt(params, 12);
                            int sec   = Utils.bytesToInt(params, 16);
                            int nsec  = Utils.bytesToInt(params, 20);
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            try {
                                if (mode == Constants.timed) {
                                    int uSec = sec * 1000000 + nsec / 1000;
                                    evs = sys.getEvents(att, mode, uSec, count);
                                }
                                else if (mode == Constants.sleep) {
                                    // There's a problem if we have a remote client that is waiting
                                    // for another event by sleeping and the events stop flowing. In
                                    // that case, the client can be killed and the ET system does NOT
                                    // know about it. Since this thread will be stuck in "getEvents",
                                    // it will not immediately detect the break in the socket - at least
                                    // not until events start flowing again. To circumvent this, implement
                                    // "sleep" by repeats of "timed" every few seconds to allow
                                    // detection of broken socket between calls to "getEvents".

                                    // Store the fact we're trying to sleep - necessary when
                                    // told to wake up.
                                    att.setSleepMode(true);

                                    tryToGetEvents:
                                    while (true) {
                                        // try a 4 second wait for events
                                        try {
                                            if (att.isWakeUp()) {
                                                att.setWakeUp(false);
                                                throw new EtWakeUpException("attachment " + att.getId() + " woken up");
                                            }
                                            evs = sys.getEvents(att, Constants.timed, 4000000, count);
                                            // no longer in sleep mode
                                            att.setSleepMode(false);
                                            // may have been told to wake up between last 2 statements.
                                            att.setWakeUp(false);
                                            break;
                                        }
                                        // if timeout, check socket to see if still open
                                        catch (EtTimeoutException tx) {
                                            try {
                                                // 1/2 second max delay on read
                                                in.readInt();
                                                // should never be able to get here
                                                att.setSleepMode(false);
                                                throw new EtException("communication protocol error");
                                            }
                                            // if there's an interrupted ex, socket is OK
                                            catch (InterruptedIOException ex) { }
                                        }
                                    }

                                }
                                else {
                                    evs = sys.getEvents(att, mode, 0, count);
                                }

                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtBusyException ex) {
                                err = Constants.errorBusy;
                            }
                            catch (EtEmptyException ex) {
                                err = Constants.errorEmpty;
                            }
                            catch (EtWakeUpException ex) {
                                err = Constants.errorWakeUp;
                                att.setSleepMode(false);
                            }
                            catch (EtTimeoutException ex) {
                                err = Constants.errorTimeout;
                            }

                            if (err != ok) {
                                out.writeInt(err);
                                out.flush();
                                break;
                            }
/*
                          // use buffered output
                          // first send number of events
                          out.writeInt(evs.length);
                          int size = evs.length * 4 * (6 + selectInts);
                          for (int j = 0; j < evs.length; j++) {
                              size += evs[j].length;
                          }
                          out.writeInt(size);
                          for (int j = 0; j < evs.length; j++) {
                              evs[j].modify = mod;
                              out.writeInt(evs[j].length);
                              out.writeInt(evs[j].memSize);
                              out.writeInt(evs[j].priority | evs[j].dataStatus << dataShift);
                              out.writeInt(evs[j].id);
                              out.writeInt(evs[j].byteOrder);
                              out.writeInt(0);
                              for (int i = 0; i < selectInts; i++) {
                                  out.writeInt(evs[j].control[i]);
                              }
                              out.write(evs[j].data, 0, evs[j].length);
                          }
                          out.flush();
*/
                            // handle buffering by hand
                            int length, index = 12;
                            int headerSize = 4 * (6 + selectInts);
                            int size = evs.length * headerSize;
                            for (EventImpl ev1 : evs) {
                                size += ev1.getLength();
                            }

                            Utils.intToBytes(evs.length, buffer, 0);
                            Utils.longToBytes((long)size, buffer, 4);

                            Modify mfy = Modify.getModify(mod);
                            for (EventImpl ev : evs) {
                                ev.setModify(mfy);
                                length = ev.getLength();
                                Utils.longToBytes((long)length, buffer, index);
                                Utils.longToBytes((long)ev.getMemSize(), buffer, index += 8);
                                Utils.intToBytes(ev.getPriority().getValue() |
                                                 ev.getDataStatus().getValue() << dataShift, buffer, index += 8);
                                Utils.intToBytes(ev.getId(), buffer, index += 4); // skip 4 bytes here
                                Utils.intToBytes(ev.getByteOrder() == ByteOrder.LITTLE_ENDIAN ?
                                                 Constants.endianLittle : Constants.endianBig,
                                                 buffer, index += 8);
                                Utils.intToBytes(0, buffer, index += 4);
                                int[] control = ev.getControl();
                                for (int i = 0; i < selectInts; i++) {
                                    Utils.intToBytes(control[i], buffer, index += 4);
                                }
                                index += 4;
                                if (index + headerSize + length > buffer.length) {
                                    out.write(buffer, 0, index);
                                    index = 0;
                                    if (headerSize + length > buffer.length / 2) {
                                        out.write(ev.getData(), 0, length);
                                        out.flush();
                                        continue;
                                    }
                                    out.flush();
                                }
                                System.arraycopy(ev.getData(), 0, buffer, index, length);
                                index += length;
                            }

                            if (index > 0) {
                                out.write(buffer, 0, index);
                                out.flush();
                            }

                            if (mod == 0) {
                                sys.putEvents(att, evs);
                            }
                            evs = null;
                        }
                        break;


                        case Constants.netEvPut: {
                            in.readFully(params, 0, 32 + 4 * selectInts);

                            int attId = Utils.bytesToInt(params, 0);
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            int id = Utils.bytesToInt(params, 4);
                            EventImpl ev = sys.getEvents().get(id);
                            // skip 4 bytes here

                            long len = Utils.bytesToLong(params, 12);
                            if (len > Integer.MAX_VALUE) {
                                throw new EtException("Event is too long for this (java) ET system");
                            }
                            ev.setLength((int) len);

                            int priAndStat = Utils.bytesToInt(params, 20);
                            ev.setPriority(Priority.getPriority(priAndStat & priorityMask));
                            ev.setDataStatus(DataStatus.getStatus((priAndStat & dataMask) >> dataShift));
                            ev.setByteOrder(Utils.bytesToInt(params, 24));
                            // last parameter is ignored

                            int index = 24;
                            int[] control = new int[selectInts];
                            for (int i = 0; i < selectInts; i++) {
                                control[i] = Utils.bytesToInt(params, index += 4);
                            }
                            ev.setControl(control);
                            // only read data if modifying everything
                            if (ev.getModify() == Modify.ANYTHING) {
                                in.readFully(ev.getData(), 0, ev.getLength());
                            }

                            EventImpl[] evArray = new EventImpl[1];
                            evArray[0] = ev;

                            sys.putEvents(att, evArray);

                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netEvsPut: {
                            in.readFully(params, 0, 16);
                            int attId           = Utils.bytesToInt(params, 0);
                            AttachmentLocal att = attachments.get(new Integer(attId));
                            int numEvents       = Utils.bytesToInt(params,  4);
                            long size           = Utils.bytesToLong(params, 8);

                            long len;
                            int  id, priAndStat, index;
                            int  byteChunk = 28 + 4 * selectInts;
                            evs = new EventImpl[numEvents];

                            for (int j = 0; j < numEvents; j++) {
                                in.readFully(params, 0, byteChunk);

                                id = Utils.bytesToInt(params, 0);
                                evs[j] = sys.getEvents().get(id);
                                // skip 4 bytes here
                             
                                len = Utils.bytesToLong(params, 8);
                                if (len > Integer.MAX_VALUE) {
                                    throw new EtException("Event is too long for this (java) ET system");
                                }
                                evs[j].setLength((int) len);

                                priAndStat        = Utils.bytesToInt(params, 16);
                                evs[j].setPriority(Priority.getPriority(priAndStat & priorityMask));
                                evs[j].setDataStatus(DataStatus.getStatus((priAndStat & dataMask) >> dataShift));
                                evs[j].setByteOrder(Utils.bytesToInt(params, 20));
                                index = 24;
                                int[] control = new int[selectInts];
                                for (int i = 0; i < selectInts; i++) {
                                    control[i] = Utils.bytesToInt(params, index += 4);
                                }
                                evs[j].setControl(control);
                                if (evs[j].getModify() == Modify.ANYTHING) {
                                    // If user increased data length beyond memSize,
                                    // use more memory.
                                    if (evs[j].getLength() > evs[j].getMemSize()) {
                                        evs[j].setData(new byte[evs[j].getLength()]);
                                        evs[j].setMemSize(evs[j].getLength());
                                    }
                                    in.readFully(evs[j].getData(), 0, evs[j].getLength());
                                }
                            }

                            sys.putEvents(att, evs);
                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netEvNew: {
                            in.readFully(params, 0, 24);
                            int  err = ok;
                            int  attId = Utils.bytesToInt(params,  0);
                            int  mode  = Utils.bytesToInt(params,  4);
                            long size  = Utils.bytesToLong(params, 8);
                            int  sec   = Utils.bytesToInt(params, 16);
                            int  nsec  = Utils.bytesToInt(params, 20);
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            if (bit64 && size > Integer.MAX_VALUE/5) {
                                out.writeInt(Constants.errorTooBig);
                                out.writeLong(0L);
                                break;
                            }

                            try {
                                if (mode == Constants.timed) {
                                    int uSec = sec * 1000000 + nsec / 1000;
                                    evs = sys.newEvents(att, mode, uSec, 1, (int)size);
                                }
                                else if (mode == Constants.sleep) {
                                    // There's a problem if we have a remote client that is waiting
                                    // for another event by sleeping and the events stop flowing. In
                                    // that case, the client can be killed and the ET system does NOT
                                    // know about it. Since this thread will be stuck in "getEvents",
                                    // it will not immediately detect the break in the socket - at least
                                    // not until events start flowing again. To circumvent this, implement
                                    // "sleep" by repeats of "timed" every few seconds to allow
                                    // detection of broken socket between calls to "getEvents".

                                    // Store the fact we're trying to sleep - necessary when
                                    // told to wake up.
                                    att.setSleepMode(true);

                                    tryToGetEvents:
                                    while (true) {
                                        // try a 4 second wait for an event
                                        try {
                                            if (att.isWakeUp()) {
                                                att.setWakeUp(false);
                                                throw new EtWakeUpException("attachment " + att.getId() + " woken up");
                                            }
                                            evs = sys.newEvents(att, Constants.timed, 4000000, 1, (int)size);
                                            // no longer in sleep mode
                                            att.setSleepMode(false);
                                            // may have been told to wake up between last 2 statements.
                                            att.setWakeUp(false);
                                            break;
                                        }
                                        // if timeout, check socket to see if still open
                                        catch (EtTimeoutException tx) {
                                            try {
                                                // 1/2 second max delay on read
                                                in.readInt();
                                                // should never be able to get here
                                                att.setSleepMode(false);
                                                throw new EtException("communication protocol error");
                                            }
                                            // if there's an interrupted ex, socket is OK
                                            catch (InterruptedIOException ex) { }
                                        }
                                    }

                                }
                                else {
                                    evs = sys.newEvents(att, mode, 0, 1, (int)size);
                                }
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtBusyException ex) {
                                err = Constants.errorBusy;
                            }
                            catch (EtEmptyException ex) {
                                err = Constants.errorEmpty;
                            }
                            catch (EtWakeUpException ex) {
                                err = Constants.errorWakeUp;
                                att.setSleepMode(false);
                            }
                            catch (EtTimeoutException ex) {
                                err = Constants.errorTimeout;
                            }

                            if (err != ok) {
                                out.writeInt(err);
                                out.writeLong(0);
                                out.flush();
                                break;
                            }

                            evs[0].setModify(Modify.ANYTHING);

                            out.writeInt(err);
                            out.writeInt(evs[0].getId());
                            out.writeInt(0); // unused
                            out.flush();
                            evs = null;
                        }
                        break;


                        case Constants.netEvsNew: {
                            in.readFully(params, 0, 28);
                            int err = ok;
                            int  attId = Utils.bytesToInt(params,  0);
                            int  mode  = Utils.bytesToInt(params,  4);
                            long size  = Utils.bytesToLong(params, 8);
                            int  count = Utils.bytesToInt(params, 16);
                            int  sec   = Utils.bytesToInt(params, 20);
                            int  nsec  = Utils.bytesToInt(params, 24);

                            AttachmentLocal att = attachments.get(new Integer(attId));

                            if (bit64 && count*size > Integer.MAX_VALUE/5) {
                                out.writeInt(Constants.errorTooBig);
                                break;
                            }

                            try {
                                if (mode == Constants.timed) {
                                    int uSec = sec * 1000000 + nsec / 1000;
                                    evs = sys.newEvents(att, mode, uSec, count, (int)size);
                                }
                                else if (mode == Constants.sleep) {
                                    // There's a problem if we have a remote client that is waiting
                                    // for another event by sleeping and the events stop flowing. In
                                    // that case, the client can be killed and the ET system does NOT
                                    // know about it. Since this thread will be stuck in "getEvents",
                                    // it will not immediately detect the break in the socket - at least
                                    // not until events start flowing again. To circumvent this, implement
                                    // "sleep" by repeats of "timed" every few seconds to allow
                                    // detection of broken socket between calls to "getEvents".

                                    // Store the fact we're trying to sleep - necessary when
                                    // told to wake up.
                                    att.setSleepMode(true);

                                    tryToGetEvents:
                                    while (true) {
                                        // try a 4 second wait for events
                                        try {
                                            if (att.isWakeUp()) {
                                                att.setWakeUp(false);
                                                throw new EtWakeUpException("attachment " + att.getId() + " woken up");
                                            }
                                            evs = sys.newEvents(att, Constants.timed, 4000000, count, (int)size);
                                            // no longer in sleep mode
                                            att.setSleepMode(false);
                                            // may have been told to wake up between last 2 statements.
                                            att.setWakeUp(false);
                                            break;
                                        }
                                        // if timeout, check socket to see if still open
                                        catch (EtTimeoutException tx) {
                                            try {
                                                // 1/2 second max delay on read
                                                in.readInt();
                                                // should never be able to get here
                                                att.setSleepMode(false);
                                                throw new EtException("communication protocol error");
                                            }
                                            // if there's an interrupted ex, socket is OK
                                            catch (InterruptedIOException ex) { }
                                        }
                                    }

                                }
                                else {
                                    evs = sys.newEvents(att, mode, 0, count, (int)size);
                                }

                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtBusyException ex) {
                                err = Constants.errorBusy;
                            }
                            catch (EtEmptyException ex) {
                                err = Constants.errorEmpty;
                            }
                            catch (EtWakeUpException ex) {
                                err = Constants.errorWakeUp;
                                att.setSleepMode(false);
                            }
                            catch (EtTimeoutException ex) {
                                err = Constants.errorTimeout;
                            }

                            if (err != ok) {
                                out.writeInt(err);
                                out.flush();
                                break;
                            }

                            // handle buffering by hand
                            int index = 0;
                            byte[] buf = new byte[4 + 4 * evs.length];

                            // first send number of events
                            Utils.intToBytes(evs.length, buf, 0);
                            for (EventImpl ev : evs) {
                                ev.setModify(Modify.ANYTHING);
                                Utils.intToBytes(ev.getId(), buf, index += 4);
                            }
                            out.write(buf);
                            out.flush();

                            evs = null;
                        }
                        break;


                        case Constants.netEvDump: {
                            int  attId = in.readInt();
                            int  id    = in.readInt();

                            AttachmentLocal att = attachments.get(new Integer(attId));
                            EventImpl ev = sys.getEvents().get(id);
                            EventImpl[] evArray = new EventImpl[1];
                            evArray[0] = ev;
                            sys.dumpEvents(att, evArray);

                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netEvsDump: {
                            int attId     = in.readInt();
                            int numEvents = in.readInt();
                            evs = new EventImpl[numEvents];
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            int id;
                            byte[] buf = new byte[4 * numEvents];
                            in.readFully(buf, 0, 4 * numEvents);
                            int index = -4;

                            for (int j = 0; j < numEvents; j++) {
                                id = Utils.bytesToInt(buf, index += 4);
                                evs[j] = sys.getEvents().get(id);
                            }

                            sys.dumpEvents(att, evs);

                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netEvsNewGrp: {
                            in.readFully(params, 0, 32);
                            int err = ok;
                            int  attId = Utils.bytesToInt(params,  0);
                            int  mode  = Utils.bytesToInt(params,  4);
                            long size  = Utils.bytesToLong(params, 8);
                            int  count = Utils.bytesToInt(params, 16);
                            int  group = Utils.bytesToInt(params, 20);
                            int  sec   = Utils.bytesToInt(params, 24);
                            int  nsec  = Utils.bytesToInt(params, 28);

                            AttachmentLocal att = attachments.get(new Integer(attId));
                            List<EventImpl> evList=null;

                            if (bit64 && count*size > Integer.MAX_VALUE/5) {
                                out.writeInt(Constants.errorTooBig);
                                break;
                            }

                            try {
                                if (mode == Constants.timed) {
                                    int uSec = sec * 1000000 + nsec / 1000;
                                    evList = sys.newEvents(att, mode, uSec, count, (int)size, group);
                                }
                                else if (mode == Constants.sleep) {
                                    // There's a problem if we have a remote client that is waiting
                                    // for another event by sleeping and the events stop flowing. In
                                    // that case, the client can be killed and the ET system does NOT
                                    // know about it. Since this thread will be stuck in "getEvents",
                                    // it will not immediately detect the break in the socket - at least
                                    // not until events start flowing again. To circumvent this, implement
                                    // "sleep" by repeats of "timed" every few seconds to allow
                                    // detection of broken socket between calls to "getEvents".

                                    // Store the fact we're trying to sleep - necessary when
                                    // told to wake up.
                                    att.setSleepMode(true);

                                    tryToGetEvents:
                                    while (true) {
                                        // try a 4 second wait for events
                                        try {
                                            if (att.isWakeUp()) {
                                                att.setWakeUp(false);
                                                throw new EtWakeUpException("attachment " + att.getId() + " woken up");
                                            }
                                            evList = sys.newEvents(att, Constants.timed, 4000000, count, (int)size, group);
                                            // no longer in sleep mode
                                            att.setSleepMode(false);
                                            // may have been told to wake up between last 2 statements.
                                            att.setWakeUp(false);
                                            break;
                                        }
                                        // if timeout, check socket to see if still open
                                        catch (EtTimeoutException tx) {
                                            try {
                                                // 1/2 second max delay on read
                                                in.readInt();
                                                // should never be able to get here
                                                att.setSleepMode(false);
                                                throw new EtException("communication protocol error");
                                            }
                                            // if there's an interrupted ex, socket is OK
                                            catch (InterruptedIOException ex) { }

                                            // original loop
//                                            while (true) {
//                                                try {
//                                                    // 1/2 second max delay on read
//                                                    in.readInt();
//                                                    // should never be able to get here
//                                                    att.setSleepMode(false);
//                                                    throw new EtException("communication protocol error");
//                                                }
//                                                // if there's an interrupted ex, socket is OK
//                                                catch (InterruptedIOException ex) {
//                                                    continue tryToGetEvents;
//                                                }
//                                                // if there's an io ex, socket is closed
//                                                catch (IOException ex) {
//                                                    throw ex;
//                                                }
//                                            }
                                        }
                                    }

                                }
                                else {
                                    evList = sys.newEvents(att, mode, 0, count, (int)size, group);
                                }

                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtBusyException ex) {
                                err = Constants.errorBusy;
                            }
                            catch (EtEmptyException ex) {
                                err = Constants.errorEmpty;
                            }
                            catch (EtWakeUpException ex) {
                                err = Constants.errorWakeUp;
                                att.setSleepMode(false);
                            }
                            catch (EtTimeoutException ex) {
                                err = Constants.errorTimeout;
                            }
System.out.println("newEvents ERROR = " + err);
                            if (err != ok) {
                                out.writeInt(err);
                                out.flush();
                                break;
                            }

                            // handle buffering by hand
                            int index = 0;
                            byte[] buf = new byte[4 + 4 * evList.size()];

                            // first send number of events
                            Utils.intToBytes(evList.size(), buf, 0);
                            for (EventImpl ev : evList) {
                                ev.setModify(Modify.ANYTHING);
                                Utils.intToBytes(ev.getId(), buf, index += 4);
                            }
                            out.write(buf);
                            out.flush();
                        }
                        break;


                        default:
                            break;
                    } // switch(command)
                }   // if (command < Constants.netAlive)


                else if (command < Constants.netStatGAtts) {
                    switch (command) {
                        case Constants.netAlive: {
                            // we must be alive by definition as this is in the ET process
                            out.writeInt(1);
                            out.flush();
                        }
                        break;


                        case Constants.netWait: {
                            // We are alive by definition and in Java there is no
                            // routine comparable to et_wait_for_alive(). This is
                            // to talk to "C" ET systems.
                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netClose:
                        case Constants.netFClose: {
                            out.writeInt(ok);

                            // detach all attachments
                            Entry ent;
                            for (Iterator i = attachments.entrySet().iterator(); i.hasNext();) {
                                ent = (Entry) i.next();
                                sys.detach((AttachmentLocal) ent.getValue());
                            }
                            if (config.getDebug() >= Constants.debugInfo) {
                                java.lang.System.out.println("commandLoop: remote client closing");
                            }
                            return;
                        }
                        // break;


                        case Constants.netWakeAtt: {
                            int attId = in.readInt();
                            // look locally for attachments
                            AttachmentLocal att = attachments.get(new Integer(attId));
                            if (att != null) {
                                att.getStation().getInputList().wakeUp(att);
                                if (att.isSleepMode()) {
                                    att.setWakeUp(true);
                                }
                            }
                        }
                        break;


                        case Constants.netWakeAll: {
                            int statId = in.readInt();
                            // Stations are stored in a linked list. Find one w/ this id.
                            synchronized (sys.getStationLock()) {
                                for (StationLocal stat : sys.getStations()) {
                                    if (stat.getStationId() == statId) {
                                        // Since attachments which sleep when getting events don't
                                        // really sleep but do a timed wait, they occasionally are
                                        // not in a get method but are checking the status of the
                                        // tcp connection. This means they don't know to wake up.
                                        // Solve this problem by setting all the station's
                                        // attachment's wake up flags, so that the next call to
                                        // getEvents will make them all wake up.

                                        for (AttachmentLocal att : stat.getAttachments()) {
                                            if (att.isSleepMode()) {
                                                att.setWakeUp(true);
                                            }
                                        }

                                        stat.getInputList().wakeUpAll();
                                        break;
                                    }
                                }
                            }
                        }
                        break;


                        case Constants.netStatAtt: {
                            int err = ok;
                            int statId = in.readInt();
                            int pid    = in.readInt();
                            int length = in.readInt();
                            String host = null;
                            AttachmentLocal att = null;

                            if (length > 0) {
                                byte buf[] = new byte[length];
                                in.readFully(buf, 0, length);
                                host = new String(buf, 0, length - 1, "ASCII");
                            }

                            try {
                                att = sys.attach(statId);
                                att.setPid(pid);
                                if (length > 0) {
                                    att.setHost(host);
                                }
                                // keep track of all attachments locally
                                attachments.put(att.getId(), att);
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }
                            catch (EtTooManyException ex) {
                                err = Constants.errorTooMany;
                            }

                            out.writeInt(err);
                            if (err == ok) {
                                out.writeInt(att.getId());
                            }
                            else {
                                out.writeInt(0);
                            }
                            out.flush();
                        }
                        break;


                        case Constants.netStatDet: {
                            int attId = in.readInt();
                            AttachmentLocal att = attachments.get(new Integer(attId));

                            sys.detach(att);

                            // keep track of all detachments locally
                            attachments.remove(att.getId());
                            out.writeInt(ok);
                            out.flush();
                        }
                        break;


                        case Constants.netStatCrAt: {
                            int err = ok;
                            StationLocal stat = null;
                            StationConfig statConfig = new StationConfig();

                            // read in station config info
                            int init = in.readInt(); // not used in Java
                            statConfig.setFlowMode(in.readInt());
                            statConfig.setUserMode(in.readInt());
                            statConfig.setRestoreMode(in.readInt());
                            statConfig.setBlockMode(in.readInt());
                            statConfig.setPrescale(in.readInt());
                            statConfig.setCue(in.readInt());
                            statConfig.setSelectMode(in.readInt());
                            int[] select = new int[Constants.stationSelectInts];
                            for (int i = 0; i < Constants.stationSelectInts; i++) {
                                select[i] = in.readInt();
                            }
                            statConfig.setSelect(select);

                            // If both a function name and library name are sent,
                            // the user thinks he's talking to a C system when
                            // it's really a Java java.lang.System. If only a single name
                            // (class) is supplied, the user knows what he's doing.
                            int lengthFunc       = in.readInt();
                            int lengthLib        = in.readInt();
                            int lengthClass      = in.readInt();
                            int lengthName       = in.readInt();
                            int position         = in.readInt();
                            int parallelPosition = in.readInt();

                            int length = (lengthClass > lengthLib) ? lengthClass : lengthLib;
                            length = (length > lengthFunc) ? length : lengthFunc;
                            length = (length > lengthName) ? length : lengthName;
                            byte[] buf = new byte[length];

                            if (lengthFunc > 0) {
                                in.readFully(buf, 0, lengthFunc);
                                statConfig.setSelectFunction(new String(buf, 0, lengthFunc - 1, "ASCII"));
                            }
                            if (lengthLib > 0) {
                                in.readFully(buf, 0, lengthLib);
                                statConfig.setSelectLibrary(new String(buf, 0, lengthLib - 1, "ASCII"));
                            }
                            if (lengthClass > 0) {
                                in.readFully(buf, 0, lengthClass);
                                statConfig.setSelectClass(new String(buf, 0, lengthClass - 1, "ASCII"));
                            }

                            in.readFully(buf, 0, lengthName);
                            String name = new String(buf, 0, lengthName - 1, "ASCII");

                            try {
                                stat = sys.createStation(statConfig, name, position, parallelPosition);
                            }
                            catch (EtTooManyException ex) {
                                err = Constants.errorTooMany;
                            }
                            catch (EtExistsException ex) {
                                err = Constants.errorExists;
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }

                            out.writeInt(err);
                            if (err != ok) {
                                out.writeInt(0);
                            }
                            else {
                                out.writeInt(stat.getStationId());
                            }
                            out.flush();
                        }
                        break;


                        case Constants.netStatRm: {
                            int err = ok;
                            int statId = in.readInt();

                            try {
                                sys.removeStation(statId);
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }

                            out.writeInt(err);
                            out.flush();
                        }
                        break;

                        case Constants.netStatSPos: {
                            int err = ok;
                            int statId = in.readInt();
                            int position = in.readInt();
                            int pposition = in.readInt();

                            try {
                                sys.setStationPosition(statId, position, pposition);
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }

                            out.writeInt(err);
                            out.flush();
                        }
                        break;


                        case Constants.netStatGPos: {
                            int position = -1, pPosition = 0;
                            int err = ok;
                            int statId = in.readInt();

                            try {
                                position  = sys.getStationPosition(statId);
                                pPosition = sys.getStationParallelPosition(statId);
                            }
                            catch (EtException ex) {
                                err = Constants.error;
                            }

                            out.writeInt(err);
                            out.writeInt(position);
                            out.writeInt(pPosition);
                            out.flush();
                        }
                        break;


                        case Constants.netStatIsAt: {
                            int attached; // not attached by default
                            int statId = in.readInt();
                            int attId = in.readInt();

                            try {
                                attached = sys.stationAttached(statId, attId) ? 1 : 0;
                            }
                            catch (EtException ex) {
                                attached = Constants.error;
                            }

                            out.writeInt(attached);
                            out.flush();
                        }
                        break;


                        case Constants.netStatEx: {
                            boolean exists = true;
                            int statId = 0;
                            int length = in.readInt();
                            byte[] buf = new byte[length];
                            in.readFully(buf, 0, length);
                            String name = new String(buf, 0, length - 1, "ASCII");

                            // in equivalent "C" function, station id is also returned
                            try {
                                statId = sys.stationNameToObject(name).getStationId();
                            }
                            catch (EtException ex) {
                                exists = false;
                            }

                            out.writeInt(exists ? 1 : 0);
                            out.writeInt(statId);
                            out.flush();
                        }
                        break;


                        case Constants.netStatSSw: {
                            StationLocal stat = null;
                            int[] select = new int[selectInts];
                            int statId = in.readInt();

                            for (int i = 0; i < selectInts; i++) {
                                select[i] = in.readInt();
                            }

                            try {
                                stat = sys.stationIdToObject(statId);
                            }
                            catch (EtException ex) {
                            }

                            if (stat != null) {
                                stat.setSelectWords(select);
                                out.writeInt(ok);
                            }
                            else {
                                out.writeInt(Constants.error);
                            }
                            out.flush();
                        }
                        break;


                        case Constants.netStatGSw: {
                            int statId = in.readInt();
                            StationLocal stat = null;

                            try {
                                stat = sys.stationIdToObject(statId);
                            }
                            catch (EtException ex) {
                            }

                            if (stat != null) {
                                out.writeInt(ok);
                                int[] select = stat.getConfig().getSelect();
                                for (int i = 0; i < selectInts; i++) {
                                    out.writeInt(select[i]);
                                }
                            }
                            else {
                                out.writeInt(Constants.error);
                            }
                            out.flush();
                        }
                        break;


                        case Constants.netStatFunc:
                        case Constants.netStatLib:
                        case Constants.netStatClass: {
                            int statId = in.readInt();
                            StationLocal stat;

                            try {
                                stat = sys.stationIdToObject(statId);
                                String returnString;
                                if (command == Constants.netStatFunc) {
                                    returnString = stat.getConfig().getSelectFunction();
                                }
                                else if (command == Constants.netStatLib) {
                                    returnString = stat.getConfig().getSelectLibrary();
                                }
                                else {
                                    returnString = stat.getConfig().getSelectClass();
                                }

                                if (returnString == null) {
                                    out.writeInt(Constants.error);
                                    out.writeInt(0);
                                }
                                else {
                                    out.writeInt(ok);
                                    out.writeInt(returnString.length() + 1);
                                    try {
                                        out.write(returnString.getBytes("ASCII"));
                                    }
                                    catch (UnsupportedEncodingException ex) {
                                    }
                                    out.writeByte(0); // C null terminator
                                }
                            }
                            catch (EtException ex) {
                                out.writeInt(Constants.error);
                                out.writeInt(-1);
                            }

                            out.flush();

                        }
                        break;


                        default :
                            ;
                    } // switch(command)
                }   // if (command < Constants.netStatGAtts)

                // the following commands get values associated with stations
                else if (command < Constants.netStatSBlock) {
                    int val = 0;
                    int statId = in.readInt();

                    StationLocal stat = null;
                    try {
                        stat = sys.stationIdToObject(statId);
                    }
                    catch (EtException ex) {
                    }

                    if (stat == null) {
                        out.writeInt(Constants.error);
                    }
                    else {
                        if (command == Constants.netStatGAtts) {
                            synchronized (sys.getStationLock()) {
                                val = stat.getAttachments().size();
                            }
                        }
                        else if (command == Constants.netStatStatus)
                            val = stat.getStatus();
                        else if (command == Constants.netStatInCnt) {
                            synchronized (stat.getInputList()) {
                                val = stat.getInputList().getEvents().size();
                            }
                        }
                        else if (command == Constants.netStatOutCnt) {
                            synchronized (stat.getOutputList()) {
                                val = stat.getOutputList().getEvents().size();
                            }
                        }
                        else if (command == Constants.netStatGBlock)
                            val = stat.getConfig().getBlockMode();
                        else if (command == Constants.netStatGUser)
                            val = stat.getConfig().getUserMode();
                        else if (command == Constants.netStatGRestore)
                            val = stat.getConfig().getRestoreMode();
                        else if (command == Constants.netStatGPre)
                            val = stat.getConfig().getPrescale();
                        else if (command == Constants.netStatGCue)
                            val = stat.getConfig().getCue();
                        else if (command == Constants.netStatGSelect)
                            val = stat.getConfig().getSelectMode();
                        else {
                            if (config.getDebug() >= Constants.debugError) {
                                java.lang.System.out.println("commandLoop: bad command value");
                            }
                            throw new EtReadException("bad command value");
                        }
                        out.writeInt(ok);
                    }
                    out.writeInt(val);
                    out.flush();
                }

                // the following commands set values associated with stations
                else if (command < Constants.netAttPut) {
                    int statId = in.readInt();
                    int val = in.readInt();

                    StationLocal stat = null;
                    try {
                        stat = sys.stationIdToObject(statId);
                    }
                    catch (EtException ex) {
                    }

                    if (stat == null) {
                        out.writeInt(Constants.error);
                    }
                    else {
                        if (command == Constants.netStatSBlock)
                            stat.setBlockMode(val);
                        else if (command == Constants.netStatSUser)
                            stat.setUserMode(val);
                        else if (command == Constants.netStatSRestore)
                            stat.setRestoreMode(val);
                        else if (command == Constants.netStatSPre)
                            stat.setPrescale(val);
                        else if (command == Constants.netStatSCue)
                            stat.setCue(val);
                        else {
                            if (config.getDebug() >= Constants.debugError) {
                                java.lang.System.out.println("commandLoop: bad command value");
                            }
                            throw new EtReadException("bad command value");
                        }
                        out.writeInt(ok);
                    }
                    out.flush();
                }

                // the following commands get values associated with attachments
                else if (command < Constants.netSysTmp) {
                    int attId = in.readInt();
                    // look locally for attachments
                    AttachmentLocal att = attachments.get(new Integer(attId));
                    if (att == null) {
                        out.writeInt(Constants.error);
                        out.writeLong(0);
                    }
                    else {
                        out.writeInt(ok);
                        if (command == Constants.netAttPut)
                            out.writeLong(att.getEventsPut());
                        else if (command == Constants.netAttGet)
                            out.writeLong(att.getEventsGet());
                        else if (command == Constants.netAttDump)
                            out.writeLong(att.getEventsDump());
                        else if (command == Constants.netAttMake)
                            out.writeLong(att.getEventsMake());
                    }
                    out.flush();
                }

                // the following commands get values associated with the system
                else if (command <= Constants.netSysGrp) {
                    int val;

                    if (command == Constants.netSysTmp)
                        val = 0; // no temps (or all temps) by definition
                    else if (command == Constants.netSysTmpMax)
                        val = 0; // no max # of temps
                    else if (command == Constants.netSysStat) {
                        synchronized (sys.getStationLock()) {
                            val = sys.getStations().size(); // # stations active or idle
                        }
                    }
                    else if (command == Constants.netSysStatMax)
                        val = sys.getConfig().getStationsMax(); // max # stations allowed
                    else if (command == Constants.netSysProc)
                        val = 0; // no processes since no shared memory
                    else if (command == Constants.netSysProcMax)
                        val = 0; // no max # of processes since no shared memory
                    else if (command == Constants.netSysAtt) {
                        synchronized (sys.getSystemLock()) {
                            val = sys.getAttachments().size(); // # attachments
                        }
                    }
                    else if (command == Constants.netSysAttMax)
                        val = sys.getConfig().getAttachmentsMax(); // max # attachments allowed
                    else if (command == Constants.netSysHBeat)
                        val = 0; // no heartbeat since no shared mem
                    else if (command == Constants.netSysPid) {
                        val = -1; // no pids in Java
                    }
                    else if (command == Constants.netSysGrp) {
                        val = sys.getConfig().getGroups().length; // number of groups
                    }
                    else {
                        if (config.getDebug() >= Constants.debugError) {
                            java.lang.System.out.println("commandLoop: bad command value");
                        }
                        throw new EtReadException("bad command value");
                    }

                    out.writeInt(ok);
                    out.writeInt(val);
                    out.flush();
                }


                else if (command <= Constants.netSysHist) {
                    // command to distribute data about this ET system over the network
                    if (command == Constants.netSysData) {
                        // allow only 1 thread at a time a crack at updating information
                        synchronized (sys.getInfoArray()) {
                            int err = sys.gatherSystemData();
                            out.writeInt(err);
                            if (err == ok) {
                                // Send data + int holding data size
                                out.write(sys.getInfoArray(), 0, sys.getDataLength() + 4);
                            }
                        }
                        out.flush();
                    }

                    // send histogram data
                    else if (command == Constants.netSysHist) {
                        // not supported under Java (yet)
                        out.writeInt(Constants.error);
                        out.flush();
                    }

                }

                else {
                    if (config.getDebug() >= Constants.debugError) {
                        java.lang.System.out.println("commandLoop: bad command value");
                    }
                    throw new EtReadException("bad command value");
                }

            } // while(true)
        }  // try

        catch (EtReadException ex) {
        }
        catch (EtException ex) {
        }
        catch (IOException ex) {
        }

        // We only end up down here if there's an error.
        // The client has crashed, therefore we must detach all
        // attachments or risked stopping the ET java.lang.System. The client
        // will not be asking for or processing any more events.

        for (Entry<Integer, AttachmentLocal> entry : attachments.entrySet()) {
            //System.out.println("Detaching from attachment key = " + entry.getKey());
            sys.detach(entry.getValue());
        }

        if (config.getDebug() >= Constants.debugError) {
            java.lang.System.out.println("commandLoop: remote client connection broken");
        }

        return;
    }

    
}
