package TextSearcher;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TextSearcher {
	
	private static ConsoleProgressBar progressBar = new ConsoleProgressBar(0,
			100, 20);
	private static double startMili;
	private static Set<Path> inputFiles = new HashSet<Path>();
	private static Set<Path> allFiles;
	private static Set<String> includeList;
	private static Set<String> excludeList;
	private static Path currentPath = Paths.get(System.getProperty("user.dir"));
	private static String pattern;
	private static Pattern regex;
	private static Path source;
	private static long totalSizeOfFiles;
	private static volatile long totalSizeReaded;
	private static String encoding="gbk";
	private static boolean caseSensitive = false;
	private static boolean wholeWord = false;
	private static boolean recursively = false;
	private static boolean regexMatch = false;
	private static boolean searchHidden = false;
	private static boolean listFiles = false;
	private static boolean showFilesOnly = false;
	private static volatile long foundNum = 0;
	private static List<Path> notFoundFiles;
	private final static String[] OPTIONS = { "-w", "--wholeWord", "-r",
			"--regex", "-c", "--caseSensitive", "-R",
			"--recursively", "-H", "--hide", "-E", "--encoding", "-L",
			"--listAllFiles", "--include", "--exclude", "--showFilesOnly" };
	private final static String FILE_SEPARATOR = System
			.getProperty("file.separator");
	private static final String LINE_SEPARATOR = System.lineSeparator();
	private static final String MANUAL = 
			LINE_SEPARATOR
			+ "TextSearcher" + LINE_SEPARATOR 
			+ "  在文件中搜索指定文本."+LINE_SEPARATOR + LINE_SEPARATOR
			+ "  使用方法: [选项..]  [文本]  [文件..]" + LINE_SEPARATOR + LINE_SEPARATOR
			+ "  选项" + LINE_SEPARATOR
			+ "      通用选项" + LINE_SEPARATOR
			+ "          -h, --help" + LINE_SEPARATOR 
			+ "              显示帮助手册."+ LINE_SEPARATOR + LINE_SEPARATOR
			+ "      文件选项"+ LINE_SEPARATOR 
			+ "          -R, --recursively" + LINE_SEPARATOR
			+ "              在目录内递归查找." + LINE_SEPARATOR
			+ "          -H, --hide "+LINE_SEPARATOR
			+ "              在隐藏文件内搜索(默认忽略隐藏文件)"+ LINE_SEPARATOR
			+ "          -E, --encoding [编码格式]" + LINE_SEPARATOR
			+ "              使用指定编码格式(默认使用GBK)" + LINE_SEPARATOR
			+ "          --include=[文件名]"+ LINE_SEPARATOR
			+ "              仅搜索名字匹配的文件名" + LINE_SEPARATOR
			+ "          --exclude=[文件名]"+ LINE_SEPARATOR
			+ "              排除名字匹配的文件名" + LINE_SEPARATOR + LINE_SEPARATOR 
			+ "      匹配选项" + LINE_SEPARATOR
			+ "          -c, --caseSensitive" + LINE_SEPARATOR
			+ "              区分大小写" + LINE_SEPARATOR
			+ "          -w, --wholeWord" + LINE_SEPARATOR
			+ "              全字匹配" + LINE_SEPARATOR
			+ "          -r, --regex" + LINE_SEPARATOR
			+ "              允许正则表达式匹配" + LINE_SEPARATOR + LINE_SEPARATOR 
			+ "      输出选项" + LINE_SEPARATOR
			+ "          -L, -listAllFiles" + LINE_SEPARATOR
			+ "              输出所有被搜索的文件" + LINE_SEPARATOR
			+ "          --showFilesOnly" + LINE_SEPARATOR
			+ "              仅显示含有指定文本的文件名"+LINE_SEPARATOR;
			

	public static void main(String[] args) {
		//开始计时
		startMili = System.currentTimeMillis();
		
		if (args.length == 0 || args[0].equals("-h")
				|| args[0].equals("--help")) {
			System.out.println(MANUAL);
			return;
		}
		totalSizeOfFiles = 0;//待搜索文件总大小
		totalSizeReaded = 0;//已搜索文件大小
		includeList = new HashSet<String>();//输入的文件名包括列表
		excludeList = new HashSet<String>();//输入的文件排除列表
		notFoundFiles = new ArrayList<Path>();//输入的但找到的文件列表
		allFiles = new TreeSet<Path>();//所有待搜索的文件

		try {
			parseArgs(args);//解析参数
			searchAllFiles();//找到所有带搜索文件
			for(Path path:notFoundFiles)//列出未找到的文件
				System.out.println("WARNING: "+path.toString()+" not found!");
			if (listFiles)//列出待搜索的文件
				listAllFiles();
			search();//搜索
			
			double endMili = System.currentTimeMillis();
			System.out.println("总耗时为："
				+ String.format("%.2f", (endMili - startMili) / 1000) + " s");
				
		} catch (Exception e) {
			System.out.println("ERROR: " + e.getMessage());
		}
	}

	//根据文件大小匹配合适的单位(B,KB,MB,GB)并返回表达式
	private static String getSizeExpr(long sizeInByte) {
		if (sizeInByte < 1024)
			return sizeInByte + "B";
		if (sizeInByte < 1024 * 1024)
			return String.format("%.2fKB", (double) sizeInByte / 1024);
		if (sizeInByte < 1024 * 1024 * 1024)
			return String.format("%.2fMB", (double) sizeInByte / 1024 / 1024);
		return String
				.format("%.2fGB", (double) sizeInByte / 1024 / 1024 / 1024);
	}

	//找出所有待搜索文件
	private static void searchAllFiles() {
		File file;
		for (Path absFilePath : inputFiles) {
			file = absFilePath.toFile();
			if (!file.exists()) {
				notFoundFiles.add(absFilePath);
			} else if (file.isFile() && searchHidden | !file.isHidden()) {
				searchAllFilesRecursively(absFilePath);
			} else if (file.isDirectory()) {
				if (recursively) {
					searchAllFilesRecursively(absFilePath);
				} else {
					System.out.println("WARNING: " + absFilePath
							+ " is a directory!");
				}
			}
		}
	}

	//递归搜索待查找文件
	private static void searchAllFilesRecursively(Path absFilePath) {
		File file = absFilePath.toFile();
		if (file.isFile() && searchHidden | !file.isHidden()) {
			try {
				String name = absFilePath.getFileName().toString();
				if (isAccepted(name)) {
					allFiles.add(absFilePath);
					totalSizeOfFiles += getFileSize(file);
				}
			} catch (IOException e) {
				return;
			}
		} else if (file.isDirectory()) {
			if (file.listFiles() != null)
				for (String childFilePath : file.list())
					searchAllFilesRecursively(absFilePath
							.resolve(childFilePath));
		}
	}

	
	//查找
	private static void search() throws Exception {
		if (totalSizeOfFiles == 0)
			throw new Exception("No valid files");

		// setRegex
		if (regexMatch)
			compileRegex();
		
		final boolean[] loop = { true };
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 5, 60,
				TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		for (Path absFilePath : allFiles) {
			executor.execute(new SearchTask(absFilePath));
		}
		executor.shutdown();

		try {
			do { // 等待所有任务完成
				loop[0] = !executor.awaitTermination(2, TimeUnit.SECONDS); // 阻塞，直到线程池里所有任务结束
			} while (loop[0]);

			progressBar.complete();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		
	}

	//将模式串编译为正则表达式
	private static void compileRegex() throws Exception {
		try {
			if (wholeWord)
				pattern = "\\b" + pattern + "\\b";
			pattern = ".*" + pattern + ".*";
			if (caseSensitive)
				regex = Pattern.compile(pattern);
			else
				regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
		} catch (IllegalArgumentException e) {
			throw new Exception("Illegal regex pattern");
		}
	}

	
	//在文件file中查找模式串并打印匹配行
	private static void search0(File file) throws Exception {
		FileInputStream FIS = new FileInputStream(file);
				BufferedReader BF = new BufferedReader(new InputStreamReader(FIS,
				encoding));
		int lineOrder = 1;//行号
		final int interval = 10000;//每扫描interval行就更新进度
		int cnt = 0;//扫描行数计数
		int matchLineNum = 0;//此文件匹配行数
		int lastAvailable = FIS.available();//上一次更新时文件剩余大小
		StringBuilder matchLines = new StringBuilder();//匹配的行
		String line;
		try {
			while ((line = BF.readLine()) != null) {
				if (match(line)) {
					try {

						matchLineNum++;
						matchLines.append("    Line " + lineOrder + ": ")
								.append(line + LINE_SEPARATOR);
					} catch (OutOfMemoryError e1) {
						//可能遇到匹配行过多的文件，matchLines过大爆内存就将匹配行分多批输出
						//更新进度条并打印当前匹配到的所有行
						progressBar.update(file.getAbsolutePath()
								+ '(' + matchLineNum + ')' + ':'
								+ LINE_SEPARATOR);
						progressBar.update(matchLines.toString());
						matchLines.setLength(0);
					}
				}
				if (cnt++ == interval) {//判断是否更新进度条
					cnt = 0;
					totalSizeReaded += lastAvailable - FIS.available();
					lastAvailable = FIS.available();
					progressBar
							.update(100 * totalSizeReaded / totalSizeOfFiles);
				}
				++lineOrder;

			}

			if (matchLines.length() != 0) {//打印匹配到的行
				String record = file.getAbsolutePath() + '(' + matchLineNum
						+ ')' + ':' + LINE_SEPARATOR + matchLines.toString();
				progressBar.update(record);
			}

		} catch (IOException e) {
			progressBar.update("WARNING: error occurs when searching in "
							+ file.getAbsoluteFile());
		} finally {
			totalSizeReaded += lastAvailable - FIS.available();
			progressBar.update(100 * totalSizeReaded / totalSizeOfFiles);
			BF.close();
		}
	}

	//在文件file中查找模式串，找到便打印文件名
	private static void search1(File file) throws Exception {
		FileInputStream FIS = new FileInputStream(file);
				BufferedReader BF = new BufferedReader(new InputStreamReader(FIS,
				encoding));
		final int interval = 10000;//每扫描interval行就更新进度
		int cnt = 0;//扫描行数计数
		int lastAvailable = FIS.available();//上一次更新时文件剩余大小
		String line;
		try {
			while ((line = BF.readLine()) != null) {
				if (match(line)) {
					FIS.skip(FIS.available());
				//	totalSizeReaded += lastAvailable - FIS.available();
					progressBar.update(100 * totalSizeReaded / totalSizeOfFiles,file.getAbsolutePath());	
					return;
				}
				if (cnt++ == interval) {//判断是否更新进度条
					cnt = 0;
					totalSizeReaded += lastAvailable - FIS.available();
					lastAvailable = FIS.available();
					progressBar
							.update(100 * totalSizeReaded / totalSizeOfFiles);
				}
			}

		} catch (IOException e) {
			progressBar.update("WARNING: error occurs when searching in "
							+ file.getAbsoluteFile());
		} finally {
			totalSizeReaded += lastAvailable - FIS.available();
			progressBar.update(100 * totalSizeReaded / totalSizeOfFiles);
			BF.close();
		}
	}
	//列出所有待搜索的文件
	private static void listAllFiles() throws Exception {
		int cnt = 1;
		File file;
		if (allFiles.isEmpty()) {
			throw new Exception("No file found!");
		}
		System.out.println("Searching in these files:");
		for (Path absFilePath : allFiles) {
			file = absFilePath.toFile();
			try {
				System.out.println(cnt++ + " - " + file + "  "
						+ getSizeExpr(getFileSize(file)));
			} catch (FileNotFoundException e) {
				System.out.println("WARRNING: file " + file + " not found!");
				e.printStackTrace();
			} catch (IOException e) {
				System.out
						.println("WARNING: IO exception occured when accessing file "
								+ file);
			}
		}
		System.out.println();
	}

	//解析参数
	private static void parseArgs(String[] args) throws Exception {
		int i = 0;
		int len = args.length;
		String arg;
		while (i < len) {
			arg = args[i];

			if (arg.equals("-E") || arg.equals("--encoding")) {
				if (args[++i].startsWith("-"))
					throw new Exception("Please input valid chatset name!");
				encoding = args[i++];
				checkEncoding();
			} else if (arg.startsWith("--include=")) {
				String pattern = arg.substring(10);
				if (excludeList.contains(pattern))
					throw new Exception(
							"File name pattern \""
									+ arg
									+ "\" is included and excluded at the same time, no file will be searched!");
				includeList.add(pattern);
				i++;
			} else if (arg.startsWith("--exclude=")) {
				String pattern = arg.substring(10);
				if (includeList.contains(pattern))
					throw new Exception(
							"file name pattern \""
									+ arg
									+ "\" is included and excluded at the same time, no file will be searched!");
				excludeList.add(pattern);
				i++;
			} else if (arg.equals("--recursively")) {
				recursively = true;
				++i;
			} else if (arg.equals("--hide")) {
				searchHidden = true;
				++i;
			} else if (arg.equals("--listAllFiles")) {
				listFiles = true;
				++i;
			} else if (arg.equals("--caseSensitive")) {
				caseSensitive = true;
				++i;
			} else if (arg.equals("--regex")) {
				regexMatch = true;
				++i;
			} else if (arg.equals("--wholeWord")) {
				wholeWord = true;
				++i;
			} else if (arg.equals("--showFilesOnly")) {
				showFilesOnly = true;
				++i;
			} else if (arg.startsWith("--")) {
				throw new Exception("Unsupported option: \"" + arg + '"');
			} else if (arg.startsWith("-")) {
				int index = 1;
				char op;
				for (; index < arg.length(); index++) {
					op = arg.charAt(index);
					switch (op) {
					case 'R':
						recursively = true;
						break;
					case 'H':
						searchHidden = true;
						break;
					case 'L':
						listFiles = true;
						break;
					case 'c':
						caseSensitive = true;
						break;
					case 'r':
						regexMatch = true;
						break;
					case 'w':
						wholeWord = true;
						break;
					default:
						throw new Exception("Unsupported option: \"" + op + '"');
					}
				}
				++i;
			} else { // 该参数不是选项
				if (pattern == null && source == null) { //作为模式串
					pattern = args[i++];
				} else { //作为文件
					do {
						Path absFilePath;
						absFilePath = currentPath.resolve(args[i++]);
						Path[] filesFound = searchFilesByName(absFilePath);//搜索所有文件名匹配的文件(针对通配符）
						if (filesFound.length == 0) {//未找到该文件名的文件
							notFoundFiles.add(absFilePath);//加入未找到文件的列表
							continue;
						}
						inputFiles.addAll(new ArrayList<Path>(Arrays//若有名字匹配的文件，加入输入文件列表
								.asList(filesFound)));
					} while (i < len && !args[i].startsWith("-"));
				}
			}
		}
		if (pattern == null) {
			throw new Exception("please input a pattern to search for");
		}
		if (inputFiles.isEmpty()) {
			throw new Exception("file or directory not found!");
		}
	}

	

	//获取文件大小
	static private long getFileSize(File file) throws FileNotFoundException,
			IOException {

		return file.length();

	}

	//判断该行是否与模式串匹配
	static private boolean match(String aLine) {
		if (!regexMatch && !wholeWord) {
			String realPattern;
			if (!caseSensitive) {
				realPattern = pattern.toLowerCase();
				aLine = aLine.toLowerCase();
			} else {
				realPattern = pattern;
			}
			return aLine.contains(realPattern);
		}
		Matcher matcher;
		matcher = regex.matcher(aLine);
		return matcher.matches();
	}

	/**
	 *通过文件名搜索匹配的所有文件
	 *按路径逐层搜索
	 */
	static private Path[] searchFilesByName(Path absFilePath) {
		ArrayList<Path> files = new ArrayList<Path>();
		String[] path = absFilePath.toString().split('\\' + FILE_SEPARATOR);
		ArrayList<Path> curFiles = new ArrayList<Path>();
		ArrayList<Path> nextFiles = new ArrayList<Path>();
		curFiles.add(absFilePath.getRoot());
		int len = path.length;
		int i;
		for (i = 1; i < len - 1; i++) {
			final String pat = path[i];
			nextFiles = new ArrayList<Path>();
			if (pat.equals("."))//当前目录
				continue;
			if (pat.equals("..")) {//父目录
				Path parent;
				for (Path fp : curFiles) {
					parent = fp.getParent();
					if (parent == null) {
						nextFiles.addAll(curFiles);
						break;
					}
					nextFiles.add(parent);
				}
				curFiles = nextFiles;
				continue;
			}
			for (Path fp : curFiles) {
				File file = fp.toFile();
				if (file.isDirectory()) {
					try {
						for (String childPath : file.list(new FilenameFilter() {
							public boolean accept(File dir, String name) {
								return new File(dir, name).isDirectory()
										&& wildCardMatch(pat, name);//匹配下一层路径名
							}
						}))
							nextFiles.add(fp.resolve(childPath));//加入下一层查找目录列表
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			curFiles = nextFiles;
		}
		if (path[len - 1].equals(".")) {
			return curFiles.toArray(new Path[curFiles.size()]);
		}
		if (path[len - 1].equals("..") && !curFiles.isEmpty()) {
			if (curFiles.get(0).getParent() == null)
				return curFiles.toArray(new Path[curFiles.size()]);
			for (Path dir : curFiles) {
				files.add(dir.getParent());
			}
			return files.toArray(new Path[files.size()]);
		}
		for (Path dir : curFiles) {//匹配路径最后一层文件名
			File f = dir.toFile();
			if (f.list() != null)
				for (String file : f.list())
					if (wildCardMatch(path[len - 1], file))
						files.add(dir.resolve(file));
		}
		return files.toArray(new Path[files.size()]);//返回找到的所有文件
	}

	//检查输入的编码是否正确
	public static void checkEncoding() throws UnsupportedEncodingException {
		"test".getBytes(encoding);
	}

	//检查文件名是否被包括（参数设定）
	private static boolean isIncluded(String filename) {
		if (includeList.isEmpty())
			return true;
		for (String pattern : includeList)
			if (wildCardMatch(pattern, filename))
				return true;
		return false;
	}
	
	//检查文件名是否被排除（参数设定）
	private static boolean isExcluded(String filename) {
		for (String pattern : excludeList)
			if (wildCardMatch(pattern, filename))
				return true;
		return false;
	}
	
	//检查文件名是否被接受
	private static boolean isAccepted(String filename) {
		return isIncluded(filename) && !isExcluded(filename);
	}
	
	public static boolean wildCardMatch(String pattern, String str) {
		pattern = pattern.replace(".","/").replaceAll("\\*", ".*").replaceAll("\\?", ".").replace("/", "\\.");
		return str.matches(pattern);
	}

	
	//搜索任务类
	static class SearchTask implements Runnable {
		private File file;

		public SearchTask(Path absFilePath) {
			this(absFilePath.toFile());
		}

		public SearchTask(File file) {
			this.file = file;
		}

		public void run() {
			try {
				if(showFilesOnly)
					search1(file);
				else
					search0(file);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
 
}
