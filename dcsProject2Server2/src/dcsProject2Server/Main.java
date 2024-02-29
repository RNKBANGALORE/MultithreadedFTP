package dcsProject2Server;

/**
 * FTP Server Launcher
 * @author Will Henry
 * @author Vincent Lee
 * @version 1.0
 * @since March 26, 2014
 */

import java.net.BindException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.net.ServerSocket;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

class TerminateWorker implements Runnable {
	private FTPServer ftpServer;
	private Socket tSocket;

	public TerminateWorker(FTPServer ftpServer, Socket tSocket) {
		this.ftpServer = ftpServer;
		this.tSocket = tSocket;
	}

	public void run() {
		System.out.println(Thread.currentThread().getName() + " TerminateWorker Started");
		try {
			// Input
			InputStreamReader iStream = new InputStreamReader(tSocket.getInputStream());
			BufferedReader reader = new BufferedReader(iStream);

			// check every 10 ms for input
			while (!reader.ready())
				Thread.sleep(10);

			// capture and parse input
			List<String> tokens = new ArrayList<String>();
			String command = reader.readLine();
			Scanner tokenize = new Scanner(command);
			// gets command
			if (tokenize.hasNext())
				tokens.add(tokenize.next());
			// gets rest of string after the command; this allows filenames with spaces:
			// 'file1 test.txt'
			if (tokenize.hasNext())
				tokens.add(command.substring(tokens.get(0).length()).trim());
			tokenize.close();
			if (Main.DEBUG)
				System.out.println(tokens.toString());

			// command selector
			switch (tokens.get(0)) {
			case "terminate":
				ftpServer.terminate(Integer.parseInt(tokens.get(1)));
				System.out.println("Terminate Interrupt=" + tokens.get(1));
				break;
			default:
				if (Main.DEBUG)
					System.out.println("TerminateWorker invalid command");
			}
		} catch (Exception e) {
			if (Main.DEBUG)
				e.printStackTrace();
		}
		System.out.println(Thread.currentThread().getName() + " TerminateWorker Exited");
	}
}

class TerminateDaemon implements Runnable {
	private FTPServer ftpServer;
	private ServerSocket tSocket;

	public TerminateDaemon(FTPServer ftpServer, ServerSocket tSocket) {
		this.ftpServer = ftpServer;
		this.tSocket = tSocket;
	}

	public void run() {
		System.out.println(Thread.currentThread().getName() + " TerminateDaemon Started");
		while (true) {
			try {
				(new Thread(new TerminateWorker(ftpServer, tSocket.accept()))).start();
			} catch (Exception e) {
				System.out
						.println(Thread.currentThread().getName() + " TerminateDaemon failed to start TerminateWorker");
			}
		}
	}
}

class NormalWorker implements Runnable {
	private FTPServer ftpServer;
	private Socket nSocket;
	private Path path;
	private List<String> tokens;

	// Input
	InputStreamReader iStream;
	BufferedReader reader;
	// Data
	DataInputStream byteStream;
	// Output
	OutputStream oStream;
	DataOutputStream dStream;

	public NormalWorker(FTPServer ftpServer, Socket nSocket) throws Exception {
		this.ftpServer = ftpServer;
		this.nSocket = nSocket;
		path = Paths.get(System.getProperty("user.dir"));

		// streams
		iStream = new InputStreamReader(nSocket.getInputStream());
		reader = new BufferedReader(iStream);
		byteStream = new DataInputStream(nSocket.getInputStream());
		oStream = nSocket.getOutputStream();
		dStream = new DataOutputStream(oStream);
	}

	public void get() throws Exception {
		// not a directory or file
		if (Files.notExists(path.resolve(tokens.get(1)))) {
			dStream.writeBytes(
					"get: " + path.resolve(tokens.get(1)).getFileName() + ": No such file or directory" + "\n");
			return;
		}
		// is a directory
		if (Files.isDirectory(path.resolve(tokens.get(1)))) {
			dStream.writeBytes("get: " + path.resolve(tokens.get(1)).getFileName() + ": Is a directory" + "\n");
			return;
		}

		//////////
		// LOCK //
		//////////
		int lockID = ftpServer.getIN(path.resolve(tokens.get(1)));
		if (Main.DEBUG)
			System.out.println(lockID);
		if (lockID == -1) {
			dStream.writeBytes(
					"get: " + path.resolve(tokens.get(1)).getFileName() + ": No such file or directory" + "\n");
			return;
		}

		// blank message
		dStream.writeBytes("\n");

		// send terminateID
		dStream.writeBytes(lockID + "\n");

		// need to figure
		Thread.sleep(100);

		if (ftpServer.terminateGET(path.resolve(tokens.get(1)), lockID)) {
			quit();
			return;
		}

		// transfer file
		byte[] buffer = new byte[1000];
		try {
			File file = new File(path.resolve(tokens.get(1)).toString());

			// write long filesize as first 8 bytes
			long fileSize = file.length();
			byte[] fileSizeBytes = ByteBuffer.allocate(8).putLong(fileSize).array();
			dStream.write(fileSizeBytes, 0, 8);

			if (ftpServer.terminateGET(path.resolve(tokens.get(1)), lockID)) {
				quit();
				return;
			}

			// write file
			BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
			int count = 0;
			while ((count = in.read(buffer)) > 0) {
				if (ftpServer.terminateGET(path.resolve(tokens.get(1)), lockID)) {
					in.close();
					quit();
					return;
				}
				dStream.write(buffer, 0, count);
			}

			in.close();
		} catch (Exception e) {
			if (Main.DEBUG)
				System.out.println("transfer error: " + tokens.get(1));
		}

		////////////
		// UNLOCK //
		////////////
		ftpServer.getOUT(path.resolve(tokens.get(1)), lockID);
	}

	public void put() throws Exception {
		// LOCK ID
		int lockID = ftpServer.putIN_ID(path.resolve(tokens.get(1)));
		if (Main.DEBUG)
			System.out.println(lockID);

		// send message ID
		dStream.writeBytes(lockID + "\n");

		//////////
		// LOCK //
		//////////
		while (!ftpServer.putIN(path.resolve(tokens.get(1)), lockID))
			Thread.sleep(10);

		if (ftpServer.terminatePUT(path.resolve(tokens.get(1)), lockID)) {
			quit();
			return;
		}

		// can write
		dStream.writeBytes("\n");

		if (ftpServer.terminatePUT(path.resolve(tokens.get(1)), lockID)) {
			quit();
			return;
		}

		// get file size
		byte[] fileSizeBuffer = new byte[8];
		byteStream.read(fileSizeBuffer);
		ByteArrayInputStream bais = new ByteArrayInputStream(fileSizeBuffer);
		DataInputStream dis = new DataInputStream(bais);
		long fileSize = dis.readLong();

		if (ftpServer.terminatePUT(path.resolve(tokens.get(1)), lockID)) {
			quit();
			return;
		}

		// receive the file
		FileOutputStream f = new FileOutputStream(new File(tokens.get(1)).toString());
		int count = 0;
		byte[] buffer = new byte[1000];
		long bytesReceived = 0;
		while (bytesReceived < fileSize) {
			if (ftpServer.terminatePUT(path.resolve(tokens.get(1)), lockID)) {
				f.close();
				quit();
				return;
			}
			count = byteStream.read(buffer);
			f.write(buffer, 0, count);
			bytesReceived += count;
		}
		f.close();

		////////////
		// UNLOCK //
		////////////
		ftpServer.putOUT(path.resolve(tokens.get(1)), lockID);
	}

	public void delete() throws Exception {
		if (!ftpServer.delete(path.resolve(tokens.get(1)))) {
			dStream.writeBytes("delete: cannot remove '" + tokens.get(1) + "': The file is locked" + "\n");
			dStream.writeBytes("\n");
			return;
		}

		try {
			boolean confirm = Files.deleteIfExists(path.resolve(tokens.get(1)));
			if (!confirm) {
				dStream.writeBytes("delete: cannot remove '" + tokens.get(1) + "': No such file" + "\n");
				dStream.writeBytes("\n");
			} else
				dStream.writeBytes("\n");
		} catch (DirectoryNotEmptyException enee) {
			dStream.writeBytes("delete: failed to remove `" + tokens.get(1) + "': Directory not empty" + "\n");
			dStream.writeBytes("\n");
		} catch (Exception e) {
			dStream.writeBytes("delete: failed to remove `" + tokens.get(1) + "'" + "\n");
			dStream.writeBytes("\n");
		}
	}

	public void ls() throws Exception {
		try {
			DirectoryStream<Path> dirStream = Files.newDirectoryStream(path);
			for (Path entry : dirStream)
				dStream.writeBytes(entry.getFileName() + "\n");
			dStream.writeBytes("\n");
		} catch (Exception e) {
			dStream.writeBytes("ls: failed to retrive contents" + "\n");
			dStream.writeBytes("\n");
		}
	}

	public void cd() throws Exception {
		try {
			// cd
			if (tokens.size() == 1) {
				path = Paths.get(System.getProperty("user.dir"));
				dStream.writeBytes("\n");
			}
			// cd ..
			else if (tokens.get(1).equals("..")) {
				if (path.getParent() != null)
					path = path.getParent();

				dStream.writeBytes("\n");
			}
			// cd somedirectory
			else {
				// not a directory or file
				if (Files.notExists(path.resolve(tokens.get(1)))) {
					dStream.writeBytes("cd: " + tokens.get(1) + ": No such file or directory" + "\n");
				}
				// is a directory
				else if (Files.isDirectory(path.resolve(tokens.get(1)))) {
					path = path.resolve(tokens.get(1));
					dStream.writeBytes("\n");
				}
				// is a file
				else {
					dStream.writeBytes("cd: " + tokens.get(1) + ": Not a directory" + "\n");
				}
			}
		} catch (Exception e) {
			dStream.writeBytes("cd: " + tokens.get(1) + ": Error" + "\n");
		}
	}

	public void mkdir() throws Exception {
		try {
			Files.createDirectory(path.resolve(tokens.get(1)));
			dStream.writeBytes("\n");
		} catch (FileAlreadyExistsException falee) {
			dStream.writeBytes("mkdir: cannot create directory `" + tokens.get(1) + "': File or folder exists" + "\n");
		} catch (Exception e) {
			dStream.writeBytes("mkdir: cannot create directory `" + tokens.get(1) + "': Permission denied" + "\n");
		}
	}

	public void pwd() throws Exception {
		// send path
		dStream.writeBytes(path + "\n");
	}

	public void quit() throws Exception {
		// close socket
		nSocket.close();
		throw new Exception();
	}

	public void run() {
		System.out.println(Thread.currentThread().getName() + " NormalWorker Started");
		exitThread: while (true) {
			try {
				// check every 10 ms for input
				while (!reader.ready())
					Thread.sleep(10);

				// capture and parse input
				tokens = new ArrayList<String>();
				String command = reader.readLine();
				Scanner tokenize = new Scanner(command);
				// gets command
				if (tokenize.hasNext())
					tokens.add(tokenize.next());
				// gets rest of string after the command; this allows filenames with spaces:
				// 'file1 test.txt'
				if (tokenize.hasNext())
					tokens.add(command.substring(tokens.get(0).length()).trim());
				tokenize.close();
				if (Main.DEBUG)
					System.out.println(tokens.toString());

				// command selector
				switch (tokens.get(0)) {
				case "get":
					get();
					break;
				case "put":
					put();
					break;
				case "delete":
					delete();
					break;
				case "ls":
					ls();
					break;
				case "cd":
					cd();
					break;
				case "mkdir":
					mkdir();
					break;
				case "pwd":
					pwd();
					break;
				case "quit":
					quit();
					break exitThread;
				default:
					System.out.println("invalid command");
				}
			} catch (Exception e) {
				break exitThread;
			}
		}
		System.out.println(Thread.currentThread().getName() + " NormalWorker Exited");
	}
}

class NormalDaemon implements Runnable {
	private FTPServer ftpServer;
	private ServerSocket nSocket;

	public NormalDaemon(FTPServer ftpServer, ServerSocket nSocket) {
		this.ftpServer = ftpServer;
		this.nSocket = nSocket;
	}

	public void run() {
		System.out.println(Thread.currentThread().getName() + " NormalDaemon Started");
		while (true) {
			try {
				(new Thread(new NormalWorker(ftpServer, nSocket.accept()))).start();
			} catch (Exception e) {
				System.out.println(Thread.currentThread().getName() + " NormalDaemon failed to start NormalWorker");
			}
		}
	}
}

class FTPServer {
	private Map<Path, ReentrantReadWriteLock> transferMap;
	private Map<Integer, Path> commandIDMap;
	private Queue<Integer> writeQueue;
	private Set<Integer> terminateSet;

	public FTPServer() {
		transferMap = new HashMap<Path, ReentrantReadWriteLock>();
		commandIDMap = new HashMap<Integer, Path>();
		writeQueue = new LinkedList<Integer>();
		terminateSet = new HashSet<Integer>();
	}

	public synchronized int getIN(Path path) {
		int commandID = 0;

		// if Path is in transferMap
		if (transferMap.containsKey(path)) {
			// try to get read lock
			if (transferMap.get(path).readLock().tryLock()) {
				// generate unique 5 digit number
				while (commandIDMap.containsKey(commandID = generateID()))
					;

				// add to commandIDMap
				commandIDMap.put(commandID, path);

				return commandID;
			}
			// didn't get lock
			else
				return -1;
		}
		// acquire lock
		else {
			// add to transferMap and get readLock
			transferMap.put(path, new ReentrantReadWriteLock());
			transferMap.get(path).readLock().lock();

			// generate unique 5 digit number
			while (commandIDMap.containsKey(commandID = generateID()))
				;

			// add to commandIDMap
			commandIDMap.put(commandID, path);

			return commandID;
		}
	}

	public synchronized void getOUT(Path path, int commandID) {
//		System.out.println(transferMap.toString());
//		System.out.println(commandIDMap.toString());

		try {
			// remove locks
			transferMap.get(path).readLock().unlock();
			commandIDMap.remove(commandID);

			if (transferMap.get(path).getReadLockCount() == 0 && !transferMap.get(path).isWriteLocked())
				transferMap.remove(path);
		} catch (Exception e) {
			if (Main.DEBUG)
				e.printStackTrace(); // TODO
		}

//		System.out.println(transferMap.toString());
//		System.out.println(commandIDMap.toString());
	}

	public synchronized int putIN_ID(Path path) {
		int commandID = 0;

		while (commandIDMap.containsKey(commandID = generateID()))
			;
		commandIDMap.put(commandID, path);

		writeQueue.add(commandID);

		return commandID;
	}

	public synchronized boolean putIN(Path path, int commandID) {
//		System.out.print("-putIN");status();

		if (writeQueue.peek() == commandID) {
			if (transferMap.containsKey(path)) {
				if (transferMap.get(path).writeLock().tryLock()) {

//					writeQueue.poll();

//					System.out.print("+putIN");status();

					return true;
				} else
					return false;
			} else {
				transferMap.put(path, new ReentrantReadWriteLock());
				transferMap.get(path).writeLock().lock();

//				writeQueue.poll();

//				System.out.print("+putIN");status();
				return true;
			}
		}
		return false;
	}

	public synchronized void putOUT(Path path, int commandID) {
//		System.out.print("-putOUT");status();

		try {
			transferMap.get(path).writeLock().unlock();
			commandIDMap.remove(commandID);
			writeQueue.poll();

			if (transferMap.get(path).getReadLockCount() == 0 && !transferMap.get(path).isWriteLocked())
				transferMap.remove(path);
		} catch (Exception e) {
			if (Main.DEBUG)
				e.printStackTrace(); // TODO
		}

//		System.out.print("+putOUT");status();
	}

	public int generateID() {
		return new Random().nextInt(90000) + 10000;
	}

	public synchronized boolean delete(Path path) {
		return !transferMap.containsKey(path);
	}

	public synchronized void terminate(int commandID) {
		terminateSet.add(commandID);
	}

	public synchronized boolean terminateGET(Path path, int commandID) {
		try {
			if (terminateSet.contains(commandID)) {
				terminateSet.remove(commandID);
				commandIDMap.remove(commandID);
				transferMap.get(path).readLock().unlock();

				if (transferMap.get(path).getReadLockCount() == 0 && !transferMap.get(path).isWriteLocked())
					transferMap.remove(path);
				return true;
			}
		} catch (Exception e) {
			if (Main.DEBUG)
				e.printStackTrace(); // TODO
		}

		return false;
	}

	public synchronized boolean terminatePUT(Path path, int commandID) {
//		System.out.print("-terminatePUT");status();

		try {
			if (terminateSet.contains(commandID)) {
				terminateSet.remove(commandID);
				commandIDMap.remove(commandID);
				transferMap.get(path).writeLock().unlock();
				writeQueue.poll();
				Files.deleteIfExists(path);

				if (transferMap.get(path).getReadLockCount() == 0 && !transferMap.get(path).isWriteLocked())
					transferMap.remove(path);

//				System.out.print("+terminatePUT");status();
				return true;
			}
		} catch (Exception e) {
			if (Main.DEBUG)
				e.printStackTrace(); // TODO
		}

		return false;
	}

	public void status() {
		System.out.println("FTPServer: transferMap-commandIDMap-writeQueue-terminateSet");
		System.out.println(transferMap.toString());
		System.out.println(commandIDMap.toString());
		System.out.println(writeQueue.toString());
		System.out.println(terminateSet.toString());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "FTPServer [transferMap=" + transferMap + ", commandIDMap=" + commandIDMap + ", writeQueue=" + writeQueue
				+ ", terminateSet=" + terminateSet + "]";
	}
}

public class Main {
	public static final boolean DEBUG = true;
	private static ServerSocket nSocket, tSocket;

	/**
	 * Repeatedly listens for incoming messages on TCP port normal commands (nport)
	 * and terminate port (tport)
	 * 
	 * @param args nport, tport
	 */
	public static void main(String[] args) {
		// number of arguments
		if (args.length != 2) {
			System.out.println("error: Invalid number of arguments");
			return;
		}

		////////////////////////////
		// port range: 1 to 65535 //
		////////////////////////////

		// nPort port number
		int nPort = 0;
		try {
			nPort = Integer.parseInt(args[0]);
			if (nPort < 1 || nPort > 65535)
				throw new Exception();
		} catch (NumberFormatException nfe) {
			System.out.println("error: Invalid nport number");
			return;
		} catch (Exception e) {
			System.out.println("error: Invalid nport range, valid ranges: 1-65535");
			return;
		}

		// tPort port number
		int tPort = 0;
		try {
			tPort = Integer.parseInt(args[1]);
			if (tPort < 1 || tPort > 65535)
				throw new Exception();
		} catch (NumberFormatException nfe) {
			System.out.println("error: Invalid tport number");
			return;
		} catch (Exception e) {
			System.out.println("error: Invalid nport range, valid ranges: 1-65535");
			return;
		}

		// port numbers must be different
		if (nPort == tPort) {
			System.out.println("error: nPort and tPort must be port numbers");
			return;
		}

		// listening sockets
		try {
			nSocket = new ServerSocket(nPort);
			tSocket = new ServerSocket(tPort);
		} catch (BindException be) {
			System.out.println("error: one or more ports are already in use");
			return;
		} catch (Exception e) {
			System.out.println("error: server could not be started");
			return;
		}

		/////////////////////////
		// FTP Server Launcher //
		/////////////////////////

		try {
			// shared memory object
			FTPServer ftpServer = new FTPServer();

			// two threads, one for each socket
			(new Thread(new NormalDaemon(ftpServer, nSocket))).start();
			(new Thread(new TerminateDaemon(ftpServer, tSocket))).start();
		} catch (Exception e) {
			System.out.println("ftp.server.Main");
			e.printStackTrace(); // TODO
		}
	}
}
