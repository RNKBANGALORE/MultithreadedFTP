package dcsProject2Client;



/**
 * FTP Client Launcher
 * @author Will Henry
 * @author Vincent Lee
 * @version 1.0
 * @since March 26, 2014
 */

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;

class Worker implements Runnable {
	private FTPClient ftpClient;
	private String hostname;
	private int nPort;
	private Socket socket;
	private Path path, serverPath;
	private List<String> tokens;
	private int terminateID;
	
	//Stream
	private InputStreamReader iStream;
	private BufferedReader reader;
	private DataInputStream byteStream; 
	private OutputStream oStream;
	private DataOutputStream dStream;
	
	
	public Worker(FTPClient ftpClient, String hostname, int nPort) throws Exception {
		this.ftpClient = ftpClient;
		this.hostname = hostname;
		this.nPort = nPort;
		
		//Connect to server
		InetAddress ip = InetAddress.getByName(hostname);
		socket = new Socket();
		socket.connect(new InetSocketAddress(ip.getHostAddress(), nPort), 1000);
		
		//Streams
		initiateStream();
		
		//Set current working directory
		path = Paths.get(System.getProperty("user.dir"));
		System.out.println("Connected to: " + ip);
	}
	
	public void initiateStream() {
		try {
			//Input
			iStream = new InputStreamReader(socket.getInputStream());
			reader = new BufferedReader(iStream);
			
			//Data
			byteStream = new DataInputStream(socket.getInputStream());
			
			//Output
			oStream = socket.getOutputStream();
			dStream = new DataOutputStream(oStream);
			
			//get server directory
			dStream.writeBytes("pwd" + "\n");
			
			//set server directory
			String get_line;
			if (!(get_line = reader.readLine()).equals("")) {
				serverPath = Paths.get(get_line);
			}
		} catch (Exception e) {
			if (Main.DEBUG) System.out.println("stream initiation error"); //TODO
		}
	}
	
	public void get() throws Exception {
		if (tokens.size() != 2) {
			invalid();
			return;
		}
		
		if (tokens.get(1).endsWith(" &")) {
			tokens.set(1, tokens.get(1).substring(0, tokens.get(1).length()-1).trim());
			//background
			
			List<String> tempList = new ArrayList<String>(tokens);
			Path tempPath = Paths.get(serverPath.toString());
			Path tempPathClient = Paths.get(path.toString());
			
			(new Thread(new GetWorker(ftpClient, hostname, nPort, tempList, tempPath, tempPathClient))).start();
			
			Thread.sleep(50);
			
			return;
		}
		
		//same transfer
		if (!ftpClient.transfer(serverPath.resolve(tokens.get(1)))) {
			System.out.println("error: file already transfering");
			return;
		}
		
		//send command
		dStream.writeBytes("get " + serverPath.resolve(tokens.get(1)) + "\n");
		
		//error messages
		String get_line;
		if (!(get_line = reader.readLine()).equals("")) {
			System.out.println(get_line);
			return;
		}
		
		//wait for terminate ID
		try {
			terminateID = Integer.parseInt(reader.readLine());
		} catch(Exception e) {
			if (Main.DEBUG) System.out.println("Invalid TerminateID");
		}
		System.out.println("TerminateID: " + terminateID);
		
		//CLIENT side locking
		ftpClient.transferIN(serverPath.resolve(tokens.get(1)), terminateID);
		
		
		//get file size
		byte[] fileSizeBuffer = new byte[8];
		byteStream.read(fileSizeBuffer);
		ByteArrayInputStream bais = new ByteArrayInputStream(fileSizeBuffer);
		DataInputStream dis = new DataInputStream(bais);
		long fileSize = dis.readLong();
		
		//receive the file
		FileOutputStream f = new FileOutputStream(new File(tokens.get(1)));
		int count = 0;
		byte[] buffer = new byte[1000];
		long bytesReceived = 0;
		while(bytesReceived < fileSize) {
			count = byteStream.read(buffer);
			f.write(buffer, 0, count);
			bytesReceived += count;
		}
		f.close();
		
		//CLIENT side un-locking
		ftpClient.transferOUT(serverPath.resolve(tokens.get(1)), terminateID);
	}
	
	public void put() throws Exception {
		if (tokens.size() != 2) {
			invalid();
			return;
		}
		
		if (tokens.get(1).endsWith(" &")) {
			tokens.set(1, tokens.get(1).substring(0, tokens.get(1).length()-1).trim());
			//background
			
			List<String> tempList = new ArrayList<String>(tokens);
			Path tempPath = Paths.get(serverPath.toString());
			
			(new Thread(new PutWorker(ftpClient, hostname, nPort, tempList, tempPath))).start();
			
			Thread.sleep(50);
			
			return;
		}
		
		//same transfer
		if (!ftpClient.transfer(serverPath.resolve(tokens.get(1)))) {
			System.out.println("error: file already transfering");
			return;
		}
		
		//not a directory or file
		if (Files.notExists(path.resolve(tokens.get(1)))) {
			System.out.println("put: " + tokens.get(1) + ": No such file or directory");
		} 
		//is a directory
		else if (Files.isDirectory(path.resolve(tokens.get(1)))) {
			System.out.println("put: " + tokens.get(1) + ": Is a directory");
		}
		//transfer file
		else {
			//send command
			dStream.writeBytes("put " + serverPath.resolve(tokens.get(1)) + "\n");
			
			//wait for terminate ID
			try {
				terminateID = Integer.parseInt(reader.readLine());
			} catch(Exception e) {
				if (Main.DEBUG) System.out.println("Invalid TerminateID");
			}
			System.out.println("TerminateID: " + terminateID);
			
			//CLIENT side locking
			ftpClient.transferIN(serverPath.resolve(tokens.get(1)), terminateID);
			
			//signal to start writing
			reader.readLine();
			
			//need to figure
			Thread.sleep(100);
			
			
			byte[] buffer = new byte[1000];
			try {
				File file = new File(path.resolve(tokens.get(1)).toString());
				
				//write long filesize as first 8 bytes
				long fileSize = file.length();
				byte[] fileSizeBytes = ByteBuffer.allocate(8).putLong(fileSize).array();
				dStream.write(fileSizeBytes, 0, 8);
				
				Thread.sleep(100);
				
				//write file
				BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
				int count = 0;
				while((count = in.read(buffer)) > 0)
					dStream.write(buffer, 0, count);
				
				in.close();
			} catch(Exception e){
				if (Main.DEBUG) System.out.println("transfer error: " + tokens.get(1));
			}
			
			//CLIENT side un-locking
			ftpClient.transferOUT(serverPath.resolve(tokens.get(1)), terminateID);
		}
	}
	
	public void delete() throws Exception {
		//only two arguments
		if (tokens.size() != 2) {
			invalid();
			return;
		}
		
		//not backgroundable
		if (tokens.get(1).endsWith(" &")) {
			notSupported();
			return;
		}
		
		//send command
		dStream.writeBytes("delete " + tokens.get(1) + "\n");
		
		//messages
		String delete_line;
		while (!(delete_line = reader.readLine()).equals(""))
		    System.out.println(delete_line);
	}
	
	public void ls() throws Exception {
		//only one argument
		if (tokens.size() != 1) {
			invalid();
			return;
		}
		
		//send command
		dStream.writeBytes("ls" + "\n");
		
		//messages
		String ls_line;
		while (!(ls_line = reader.readLine()).equals(""))
		    System.out.println(ls_line);
	}
	
	public void cd() throws Exception {
		//up to two arguments
		if (tokens.size() > 2) {
			invalid();
			return;
		}
		
		//not backgroundable
		if (tokens.get(tokens.size()-1).endsWith(" &")) {
			notSupported();
			return;
		}
		
		//send command
		if (tokens.size() == 1) //allow "cd" goes back to home directory
			dStream.writeBytes("cd" + "\n");
		else
			dStream.writeBytes("cd " + tokens.get(1) + "\n");
		
		//messages
		String cd_line;
		if (!(cd_line = reader.readLine()).equals(""))
			System.out.println(cd_line);
		
		//get server directory
		dStream.writeBytes("pwd" + "\n");
		
		//set server directory
		String get_line;
		if (!(get_line = reader.readLine()).equals(""))
			serverPath = Paths.get(get_line);
	}
	
	public void mkdir() throws Exception {
		//only two arguments
		if (tokens.size() != 2) {
			invalid();
			return;
		}
		
		//not backgroundable
		if (tokens.get(1).endsWith(" &")) {
			notSupported();
			return;
		}
		
		//send command
		dStream.writeBytes("mkdir " + tokens.get(1) + "\n");
		
		//messages
		String mkdir_line;
		if (!(mkdir_line = reader.readLine()).equals(""))
			System.out.println(mkdir_line);
	}
	
	public void pwd() throws Exception {
		//only one argument
		if (tokens.size() != 1) {
			invalid();
			return;
		}
		
		//send command
		dStream.writeBytes("pwd" + "\n");
		
		//message
		System.out.println(reader.readLine());
	}
	
	public void quit() throws Exception {
		//only one argument
		if (tokens.size() != 1) {
			invalid();
			return;
		}
		
		if (!ftpClient.quit()) {
			System.out.println("error: Transfers in progress");
			return;
		}
		
		//send command
		dStream.writeBytes("quit" + "\n");
	}
	
	public void terminate() throws Exception {
		//only two arguments
		if (tokens.size() != 2) {
			invalid();
			return;
		}
		
		//not backgroundable
		if (tokens.get(1).endsWith(" &")) {
			notSupported();
			return;
		}
		
		try {
			int terminateID = Integer.parseInt(tokens.get(1));
			if (!ftpClient.terminateADD(terminateID))
				System.out.println("Invalid TerminateID");
			else
				(new Thread(new TerminateWorker(hostname, Main.tPort, terminateID))).start();
		} catch (Exception e) {
			System.out.println("Invalid TerminateID");
		}
	}
	
	public void notSupported() {
		System.out.println("This command is not backgroundable.");
	}
	
	public void invalid() {
		System.out.println("Invalid Arguments");
		System.out.println("Try `help' for more information.");
	}
	
	public void help() {
		System.out.println("Available commands:");
		System.out.println(" get (get <remote_filename>) \t\t  – Copy file with the name <remote_filename> from remote directory to local directory.");
		System.out.println(" put (put <local_filename>) \t\t  – Copy file with the name <local_filename> from local directory to remote directory.");
		System.out.println(" delete (delete <remote_filename>) \t  – Delete the file with the name <remote_filename> from the remote directory.");
		System.out.println(" ls (ls) \t\t\t\t  – List the files and subdirectories in the remote directory.");
		System.out.println(" cd (cd <remote_direcotry_name> or cd ..) – Change to the <remote_direcotry_name> on the remote machine or change to the parent directory of the current directory.");
		System.out.println(" mkdir (mkdir <remote_directory_name>) \t  – Create directory named <remote_direcotry_name> as the sub-directory of the current working directory on the remote machine.");
		System.out.println(" pwd (pwd) \t\t\t\t  – Print the current working directory on the remote machine.");
		System.out.println(" terminate (terminate <command-ID> \t  – terminate the command identiied by <command-ID>.");
		System.out.println(" quit (quit) \t\t\t\t  – End the FTP session.");
	}
	
	public void input() {
		try {
			//keyboard input
			Scanner input = new Scanner(System.in);
			String command;
			
			do {
				//get input
				System.out.print(Main.PROMPT);
				command = input.nextLine();
				command = command.trim();
				
				//parse input into tokens
				tokens = new ArrayList<String>();
				Scanner tokenize = new Scanner(command);
				//gets command
				if (tokenize.hasNext())
				    tokens.add(tokenize.next());
				//gets rest of string after the command; this allows filenames with spaces: 'file1 test.txt'
				if (tokenize.hasNext())
					tokens.add(command.substring(tokens.get(0).length()).trim());
				tokenize.close();
				if (Main.DEBUG) System.out.println(tokens);
				
				//allows for blank enter
				if (tokens.isEmpty())
					continue;
				
				//command selector
				switch(tokens.get(0)) {
					case "get": 		get(); 			break;
					case "put": 		put(); 			break;
					case "delete": 		delete(); 		break;
					case "ls": 			ls(); 			break;
					case "cd": 			cd(); 			break;
					case "mkdir": 		mkdir(); 		break;
					case "pwd": 		pwd(); 			break;
					case "quit": 		quit(); 		break;
					case "help": 		help(); 		break;
					case "terminate":	terminate();	break;
					default:
						System.out.println("unrecognized command '" + tokens.get(0) + "'");
						System.out.println("Try `help' for more information.");
				}
			} while (!command.equalsIgnoreCase("quit"));
			input.close();
			
			
			System.out.println(Main.EXIT_MESSAGE);
		} catch (Exception e) {
			System.out.println("error: disconnected from host");
			if (Main.DEBUG) e.printStackTrace(); //TODO
		}
	}
	
	public void run() {
		input();
	}
}


class TerminateWorker implements Runnable {
	private Socket socket;
	private OutputStream oStream;
	private DataOutputStream dStream;
	private int terminateID;
	
	public TerminateWorker(String hostname, int tPort, int terminateID) throws Exception {
		this.terminateID = terminateID;
		
		InetAddress ip = InetAddress.getByName(hostname);
		socket = new Socket();
		socket.connect(new InetSocketAddress(ip.getHostAddress(), tPort), 1000);
		
		oStream = socket.getOutputStream();
		dStream = new DataOutputStream(oStream);
	}
	
	public void run() {
		try {
			dStream.writeBytes("terminate " + terminateID + "\n");
		} catch (IOException e) {
			if (Main.DEBUG) System.out.println("TerminateWorker");
		}
	}
}


 class PutWorker implements Runnable {
	private FTPClient ftpClient;
	private Socket socket;
	private Path path, serverPath;
	private List<String> tokens;
	private int terminateID;
	
	//Stream
	private InputStreamReader iStream;
	private BufferedReader reader;
	private OutputStream oStream;
	private DataOutputStream dStream;
	
	
	public PutWorker(FTPClient ftpClient, String hostname, int nPort, List<String> tokens, Path serverPath) throws Exception {
		this.ftpClient = ftpClient;
		this.tokens = tokens;
		this.serverPath = serverPath;
		
		InetAddress ip = InetAddress.getByName(hostname);
		socket = new Socket();
		socket.connect(new InetSocketAddress(ip.getHostAddress(), nPort), 1000);
		
		iStream = new InputStreamReader(socket.getInputStream());
		reader = new BufferedReader(iStream);
		oStream = socket.getOutputStream();
		dStream = new DataOutputStream(oStream);
		
		path = Paths.get(System.getProperty("user.dir"));
	}
	
	public void put() throws Exception {
		//same transfer
		if (!ftpClient.transfer(serverPath.resolve(tokens.get(1)))) {
			System.out.println("error: file already transfering");
			return;
		}
		
		//not a directory or file
		if (Files.notExists(path.resolve(tokens.get(1)))) {
			System.out.println("put: " + tokens.get(1) + ": No such file or directory");
		} 
		//is a directory
		else if (Files.isDirectory(path.resolve(tokens.get(1)))) {
			System.out.println("put: " + tokens.get(1) + ": Is a directory");
		}
		//transfer file
		else {
			//send command
			dStream.writeBytes("put " + serverPath.resolve(tokens.get(1)) + "\n");
			
			//wait for terminate ID
			try {
				terminateID = Integer.parseInt(reader.readLine());
			} catch(Exception e) {
				if (Main.DEBUG) System.out.println("Invalid TerminateID");
			}
			System.out.println("TerminateID: " + terminateID);
			
			//CLIENT side locking
			ftpClient.transferIN(serverPath.resolve(tokens.get(1)), terminateID);
			
			if (ftpClient.terminatePUT(serverPath.resolve(tokens.get(1)), terminateID)) return;
			
			//signal to start writing
			reader.readLine();
			
			//need to figure
			Thread.sleep(100);
			
			if (ftpClient.terminatePUT(serverPath.resolve(tokens.get(1)), terminateID)) return;
			
			byte[] buffer = new byte[1000];
			try {
				File file = new File(path.resolve(tokens.get(1)).toString());
				
				//write long filesize as first 8 bytes
				long fileSize = file.length();
				byte[] fileSizeBytes = ByteBuffer.allocate(8).putLong(fileSize).array();
				dStream.write(fileSizeBytes, 0, 8);
				
				if (ftpClient.terminatePUT(serverPath.resolve(tokens.get(1)), terminateID)) return;
				
				//write file
				BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
				int count = 0;
				while((count = in.read(buffer)) > 0) {
					if (ftpClient.terminatePUT(serverPath.resolve(tokens.get(1)), terminateID)) {
						in.close();
						return;
					}
					dStream.write(buffer, 0, count);
				}
				
				in.close();
			} catch(Exception e){
				if (Main.DEBUG) System.out.println("transfer error: " + tokens.get(1));
			}
			
			//CLIENT side un-locking
			ftpClient.transferOUT(serverPath.resolve(tokens.get(1)), terminateID);
		}
	}
	
	public void run() {
		try {
			put();
			Thread.sleep(100);
			dStream.writeBytes("quit" + "\n");
		} catch (Exception e) {
			if (Main.DEBUG) System.out.println("PutWorker error");
		}
	}
}

class GetWorker implements Runnable {
	private FTPClient ftpClient;
	private Socket socket;
	private Path serverPath, path;
	private List<String> tokens;
	private int terminateID;
	
	//Stream
	private InputStreamReader iStream;
	private BufferedReader reader;
	private DataInputStream byteStream; 
	private OutputStream oStream;
	private DataOutputStream dStream;
	
	public GetWorker(FTPClient ftpClient, String hostname, int nPort, List<String> tokens, Path serverPath, Path path) throws Exception {
		this.ftpClient = ftpClient;
		this.tokens = tokens;
		this.serverPath = serverPath;
		this.path = path;
		
		InetAddress ip = InetAddress.getByName(hostname);
		socket = new Socket();
		socket.connect(new InetSocketAddress(ip.getHostAddress(), nPort), 1000);
		
		iStream = new InputStreamReader(socket.getInputStream());
		reader = new BufferedReader(iStream);
		byteStream = new DataInputStream(socket.getInputStream());
		oStream = socket.getOutputStream();
		dStream = new DataOutputStream(oStream);
	}
	
	public void get() throws Exception {
		//same transfer
		if (!ftpClient.transfer(serverPath.resolve(tokens.get(1)))) {
			System.out.println("error: file already transfering");
			return;
		}
		
		//send command
		dStream.writeBytes("get " + serverPath.resolve(tokens.get(1)) + "\n");
		
		//error messages
		String get_line;
		if (!(get_line = reader.readLine()).equals("")) {
			System.out.println(get_line);
			return;
		}
		
		//wait for terminate ID
		try {
			terminateID = Integer.parseInt(reader.readLine());
		} catch(Exception e) {
			if (Main.DEBUG) System.out.println("Invalid TerminateID");
		}
		System.out.println("TerminateID: " + terminateID);
		
		//CLIENT side locking
		ftpClient.transferIN(serverPath.resolve(tokens.get(1)), terminateID);
		
		if (ftpClient.terminateGET(path.resolve(tokens.get(1)), serverPath.resolve(tokens.get(1)), terminateID)) return;
		
		//get file size
		byte[] fileSizeBuffer = new byte[8];
		byteStream.read(fileSizeBuffer);
		ByteArrayInputStream bais = new ByteArrayInputStream(fileSizeBuffer);
		DataInputStream dis = new DataInputStream(bais);
		long fileSize = dis.readLong();
		
		if (ftpClient.terminateGET(path.resolve(tokens.get(1)), serverPath.resolve(tokens.get(1)), terminateID)) return;
		
		//receive the file
		FileOutputStream f = new FileOutputStream(new File(tokens.get(1)));
		int count = 0;
		byte[] buffer = new byte[8192];
		long bytesReceived = 0;
		while(bytesReceived < fileSize) {
			if (ftpClient.terminateGET(path.resolve(tokens.get(1)), serverPath.resolve(tokens.get(1)), terminateID)) {
				f.close();
				return;
			}
			count = byteStream.read(buffer);
			f.write(buffer, 0, count);
			bytesReceived += count;
		}
		f.close();
		
		//CLIENT side un-locking
		ftpClient.transferOUT(serverPath.resolve(tokens.get(1)), terminateID);
	}
	
	public void run() {
		try {
			get();
			Thread.sleep(100);
			dStream.writeBytes("quit" + "\n");
		} catch (Exception e) {
			if (Main.DEBUG) System.out.println("GetWorker error");
		}
	}
}
 class FTPClient {
	private Set<Path> transferSet;
	private Set<Integer> terminateSet;
	private Map<Integer, Path> commandIDMap;
	
	public FTPClient() {
		transferSet = new HashSet<Path>();
		terminateSet = new HashSet<Integer>();
		commandIDMap = new HashMap<Integer, Path>();
	}
	
	public synchronized boolean transfer(Path path) {
		return !transferSet.contains(path);
	}
	
	public synchronized void transferIN(Path path, int commandID) {
		transferSet.add(path);
		commandIDMap.put(commandID, path);
	}
	
	public synchronized void transferOUT(Path path, int commandID) {
		try {
			transferSet.remove(path);
			commandIDMap.remove(commandID);
		} catch(Exception e) {}
	}
	
	public synchronized boolean quit() {
		return transferSet.isEmpty();
	}
	
	public synchronized boolean terminateADD(int commandID) {
		if (commandIDMap.containsKey(commandID)) {
			terminateSet.add(commandID);
			return true;
		} else
			return false;
	}
	
	public synchronized boolean terminateGET(Path path, Path serverPath, int commandID) {
		try {
			if (terminateSet.contains(commandID)) {
				commandIDMap.remove(commandID);
				transferSet.remove(serverPath);
				terminateSet.remove(commandID);
				Files.deleteIfExists(path);
				return true;
			}
		} catch (Exception e) {}
		
		return false;
	}
	
	public synchronized boolean terminatePUT(Path path, int commandID) {
		try {
			if (terminateSet.contains(commandID)) {
				commandIDMap.remove(commandID);
				transferSet.remove(path);
				terminateSet.remove(commandID);
				return true;
			}
		} catch (Exception e) {}
		
		return false;
	}
}

public class Main {
	public static final boolean DEBUG = false;
	public static final String PROMPT = "mytftp>";
	public static final String EXIT_MESSAGE = "FTP session ended. Bye!";
	public static int nPort, tPort;
	public static String hostname;
	
	/**
	 * FTP client launcher which connects to remote host
	 * @param args machineName, nPort, tPort
	 */
	public static void main(String[] args) {
		//number of arguments
		if (args.length != 3) {
			System.out.println("error: Invalid number of arguments");
			return;
		}
		
		//hostname
		try {
			InetAddress.getByName(args[0]);
			hostname = args[0];
		} catch (Exception e) {
			System.out.println("error: hostname does not resolve to an IP address");
			return;
		}
		
		////////////////////////////
		// port range: 1 to 65535 //
		////////////////////////////
		
		//nPort port number
		try {
			nPort = Integer.parseInt(args[1]);
			if (nPort < 1 || nPort > 65535) throw new Exception();
		} catch (NumberFormatException nfe) {
			System.out.println("error: Invalid nport number");
			return;
		} catch (Exception e) {
			System.out.println("error: Invalid nport range, valid ranges: 1-65535");
			return;
		}
		
		//tPort port number
		try {
			tPort = Integer.parseInt(args[2]);
			if (tPort < 1 || tPort > 65535) throw new Exception();
		} catch (NumberFormatException nfe) {
			System.out.println("error: Invalid tport number");
			return;
		} catch(Exception e) {
			System.out.println("error: Invalid nport range, valid ranges: 1-65535");
			return;
		}
		
		//port numbers must be different
		if (nPort == tPort) {
			System.out.println("error: nPort and tPort must be port numbers");
			return;
		}
		
		/////////////////////////
		// FTP Client Launcher //
		/////////////////////////
		
		try {
			//shared memory object
			FTPClient ftpClient = new FTPClient();
			
			//initial starting thread
			(new Thread(new Worker(ftpClient, hostname, nPort))).start();
		} catch (SocketTimeoutException ste) {
			System.out.println("error: host could not be reached");
		} catch (ConnectException ce) {
			System.out.println("error: no running FTP at remote host");
		} catch (Exception e) {
			System.out.println("error: program quit unexpectedly");
		}
	}
}