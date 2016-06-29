/*
	The MIT License (MIT)
	
	Copyright (c) 2016 Tobias Klevenz (tobias.klevenz@gmail.com)
	
	Permission is hereby granted, free of charge, to any person obtaining a copy
	of this software and associated documentation files (the "Software"), to deal
	in the Software without restriction, including without limitation the rights
	to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
	copies of the Software, and to permit persons to whom the Software is
	furnished to do so, subject to the following conditions:
	
	The above copyright notice and this permission notice shall be included in all
	copies or substantial portions of the Software.
	
	THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
	IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
	FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
	AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
	LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
	OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
	SOFTWARE.
*/

package co.pugo.convert;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.w3c.tidy.Tidy;

/**
 * @author Tobias Klevenz (tobias.klevenz@gmail.com)
 * 		   This work is part of a paper for M.CSc at Trier University of Applied Science.
 */
@SuppressWarnings("serial")
public class ConvertServlet extends HttpServlet {

	static final Logger LOG = Logger.getLogger(ConvertServlet.class.getSimpleName());
	private static final String CONFIG_DIR = "/WEB-INF/CONFIG/";

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// parse parameters
		Parameters parameters = new Parameters(request.getParameterMap());
		if (!hasRequiredParameters(parameters, response)) return;

		// read config file
		Configuration configuration = new Configuration(getConfigFile(parameters.getMode()));

		// set response properties
		setResponseProperties(response, configuration.getMimeType(), parameters.getFname());

		// get URLConnection
		URLConnection urlConnection = getSourceUrlConnection(parameters.getSource(), parameters.getToken());

		// convert html source to xhtml
		InputStream html = urlConnection.getInputStream();
		ByteArrayOutputStream xhtml = new ByteArrayOutputStream();
		tidyHtml(html, xhtml);

		// read xhtml to a String
		String content = IOUtils.toString(new ByteArrayInputStream(xhtml.toByteArray()));

		// close streams
		IOUtils.closeQuietly(html);
		IOUtils.closeQuietly(xhtml);

		// process images
		if (configuration.isProcessImagesSet()) {
			Set<String> imageLinks = extractImageLinks(content);
			if (imageLinks != null)
				content = replaceImgSrcWithBase64(content, downloadImageData(imageLinks));
		}

		// xsl transformation
		setupAndRunXSLTransformation(response, configuration, content, parameters.getXslParameters());
	}

	/**
	 * get config file as Stream based on provided mode
 	 * @param mode output mode set by parameter
	 * @return Config File as InputStream
	 */
	private InputStream getConfigFile(String mode) {
		InputStream configFileStream = null;
		if (mode != null)
			configFileStream = getServletContext().getResourceAsStream(CONFIG_DIR + "config_" + mode + ".xml");
		if (mode == null || configFileStream == null)
			configFileStream = getServletContext().getResourceAsStream(CONFIG_DIR + "config.xml");
		return configFileStream;
	}

	/**
	 * check if required parameters are set
	 * print error message if not
	 * @param response HttpServletResponse
	 * @param parameters local Parameters object
	 * @return success
	 */
	private boolean hasRequiredParameters(Parameters parameters, HttpServletResponse response)
		throws IOException{
		if (parameters.getSource() == null || parameters.getFname() == null) {
			response.getWriter().println("Required Parameters: " +
					"source=[Source URL], " +
					"fname=[Output Filename] \n" +
					"Optional Parameters: " +
					"token=[OAuth token] (provide if required by Source URL), " +
					"mode=[md, epub, ...], " +
					"xslParam_<XSLT Parameter Name>");
			return false;
		} else {
			return true;
		}
	}

	/**
	 * set properties of ServletResponse
	 * @param response HttpServletResponse
	 */
	private void setResponseProperties(HttpServletResponse response, String mimeType, String fileName) {
		response.setContentType(mimeType + "; charset=utf-8");
		response.setHeader("Content-Disposition", "attachment; filename='" + fileName);
	}

	/**
	 * retrieve URLConnection for source document
	 * @return URLConnection
	 * @throws IOException
	 */
	private URLConnection getSourceUrlConnection(String source, String token) throws IOException {
		URLConnection urlConnection;
		String sourceUrl = URLDecoder.decode(source, "UTF-8");
		URL url = new URL(sourceUrl);
		urlConnection = url.openConnection();
		if (token != null) {
			urlConnection.setRequestProperty("Authorization", "Bearer " + token);
		}
		return urlConnection;
	}


	/**
	 * extract a set of image links
	 * @param content document content as String
	 * @return Set of http links to images
	 */
	private Set<String> extractImageLinks(String content) {
		final Set<String> imageLinks = new HashSet<>();
		final Scanner scanner = new Scanner(content);
		final Pattern imgPattern = Pattern.compile("<img(.*?)>", Pattern.DOTALL);
		final Pattern srcPattern = Pattern.compile("src=\"(.*?)\"");

		Matcher matchSrc;
		String imgMatch;

		while (scanner.findWithinHorizon(imgPattern, 0) != null) {
			imgMatch = scanner.match().group(1);
			matchSrc = srcPattern.matcher(imgMatch);
			if (matchSrc.find())
				imageLinks.add(matchSrc.group(1));
		}

		scanner.close();
		return imageLinks;
	}

	/**
	 * download imageData and encode it base64
	 * @param imageLinks set of image links extracted with extractImageLinks()
	 * @return map, key = imageLink, value = base64 encoded image
	 */
	private HashMap<String, String> downloadImageData(Set<String> imageLinks) {
		HashMap<String, String> imageData = new HashMap<>();
		ExecutorService service = Executors.newCachedThreadPool();
		for (final String imageLink : imageLinks) {
			RunnableFuture<byte[]> future = new FutureTask<>(new Callable<byte[]>() {
				@Override
				public byte[] call() {
					try {
						URL srcUrl = new URL(imageLink);
						URLConnection urlConnection = srcUrl.openConnection();
						return IOUtils.toByteArray(urlConnection.getInputStream());
					} catch (IOException e) {
						LOG.severe(e.getMessage());
						return null;
					}
				}
			});
			service.execute(future);
			try {
				imageData.put(imageLink, Base64.encodeBase64String(future.get()));
			} catch (InterruptedException | ExecutionException e) {
				LOG.severe(e.getMessage());
			}
		}
		service.shutdown();
		try {
			service.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			LOG.severe(e.getMessage());
		}
		return imageData;
	}

	/**
	 * search and replace image links with base64 encoded image
	 * @param content document content as String
	 * @param imageData map of extracted imageData
	 * @return content after image links have been replaced with base64 code
	 */
	private String replaceImgSrcWithBase64(String content, Map<String, String> imageData) {
		for (Entry<String, String> entry : imageData.entrySet()) {
			String base64String = entry.getValue();
			Tika tika = new Tika();
			String mimeType = tika.detect(Base64.decodeBase64(base64String));
			content = content.replaceAll(entry.getKey(),
					Matcher.quoteReplacement("data:" + mimeType + ";base64," + base64String));
		}
		return content;
	}

	/**
	 * run JTidy to convert html to xhtml
	 * @param html InputStream of html data
	 * @param xhtml OutputStream for xhtml data
	 */
	private void tidyHtml(InputStream html, OutputStream xhtml) {
		Tidy tidy = new Tidy();
		tidy.setXHTML(true);
		tidy.parse(html, xhtml);
	}

	/**
	 * Setup XSL Transformation and execute
	 * @param response HttpServletResponse
	 * @param configuration object
	 * @param content document content as String
	 * @param xslParameters map of parameters passed to the transformer
	 * @throws IOException
	 */
	private void setupAndRunXSLTransformation(HttpServletResponse response, Configuration configuration,
											  String content, Map<String, String> xslParameters) throws IOException {
		InputStream source = IOUtils.toInputStream(content, "utf-8");
		InputStream xsl = getServletContext().getResourceAsStream(CONFIG_DIR + configuration.getXsl());
		Transformation transformation;

		if (configuration.isZipOutputSet())
			transformation = new Transformation(xsl, source, new ZipOutputStream(response.getOutputStream()));
		else
			transformation = new Transformation(xsl, source, response.getWriter());

		transformation.setParameters(xslParameters);
		transformation.transform();
	}
}