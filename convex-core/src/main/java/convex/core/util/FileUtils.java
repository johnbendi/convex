package convex.core.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileUtils {

	public static String loadFileAsString(String fname) throws IOException {
		String result = null;
		fname = fname.trim();
		if ("-".equals(fname)) {
			byte[] bs = System.in.readAllBytes();
			result = new String(bs);
		} else {
			Path path = Paths.get(fname);
			if (!path.toFile().exists()) {
				throw new FileNotFoundException("File does not exist: " + path);
			}
			result = Files.readString(path, StandardCharsets.UTF_8);
		}

		return result;
	}

	public static void writeFileAsString(Path file, String content) throws IOException {
		byte[] bs=content.getBytes(StandardCharsets.UTF_8);
		Files.write(file, bs);
	}

	/**
	 * Create a path if necessary to a File object. Interprets leading "~" as user home directory.
	 *
	 * @param file File object to see if the path part of the filename exists, if not then create it.
	 * @return target File, as an absolute path, with parent directories created recursively if needed
	 * @throws IOException In case of IO Error
	 */
	public static File ensureFilePath(File file) throws IOException {
		// Get path of parent directory, using absolute path (may be current working directory user.dir)
		File target=FileUtils.getFile(file.getPath());
		String dirPath=target.getParent();
		Files.createDirectories(Path.of(dirPath));
		return target;
	}

	/**
	 * Gets the absolute path File for a given file name. Interprets leading "~" as user home directory.
	 * @param path Path as a string
	 * @return File representing the given path
	 */
	public static File getFile(String path) {
		if (path!=null && path.startsWith("~")) {
			path=System.getProperty("user.home")+path.substring(1);
			return new File(path);
		} else {
			path=new File(path).getAbsolutePath();
			return new File(path);
		}
	}

}
