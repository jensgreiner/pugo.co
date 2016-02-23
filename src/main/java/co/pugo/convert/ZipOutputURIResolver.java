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

import java.io.IOException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.transform.Result;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;

import net.sf.saxon.lib.OutputURIResolver;

public class ZipOutputURIResolver implements OutputURIResolver {
	
	private ZipOutputStream zos;
	
	public ZipOutputURIResolver(ZipOutputStream zos) {
		super();
		this.zos = zos;
	}

	@Override
	public void close(Result arg0) throws TransformerException {}

	@Override
	public OutputURIResolver newInstance() {
		// TODO Auto-generated method stub
		return new ZipOutputURIResolver(zos);
	}

	@Override
	public Result resolve(String href, String base) throws TransformerException {
		try {
			zos.putNextEntry(new ZipEntry(href));
		} catch (IOException e) {
			e.printStackTrace();
		}
		Result result = new StreamResult(zos);
		result.setSystemId(UUID.randomUUID().toString());
		return result;
	}

}
