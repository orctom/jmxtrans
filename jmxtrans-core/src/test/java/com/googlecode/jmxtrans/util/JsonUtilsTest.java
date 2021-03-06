/**
 * The MIT License
 * Copyright (c) 2010 JmxTrans team
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.googlecode.jmxtrans.util;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.inject.Injector;
import com.googlecode.jmxtrans.cli.JmxTransConfiguration;
import com.googlecode.jmxtrans.guice.JmxTransModule;
import com.googlecode.jmxtrans.model.JmxProcess;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.test.RequiresIO;
import com.kaching.platform.testing.AllowLocalFileAccess;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import javax.annotation.Nullable;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static com.google.common.collect.FluentIterable.from;
import static org.assertj.core.api.Assertions.assertThat;

@Category(RequiresIO.class)
@AllowLocalFileAccess(paths = "*")
public class JsonUtilsTest {

	private JsonUtils jsonUtils;

	@Before
	public void setupJsonUtils() {
		Injector injector = JmxTransModule.createInjector(new JmxTransConfiguration());
		jsonUtils = injector.getInstance(JsonUtils.class);
	}

	@Test
	public void loadingFromFile() throws URISyntaxException, IOException, MalformedObjectNameException {
		File input = new File(JsonUtilsTest.class.getResource("/example.json").toURI());

		JmxProcess process = jsonUtils.parseProcess(input);
		assertThat(process.getName()).isEqualTo("example.json");

		Server server = process.getServers().get(0);
		assertThat(server.getNumQueryThreads()).isEqualTo(2);

		Optional<Query> queryOptional = from(server.getQueries()).firstMatch(new ByObj("java.lang:type=Memory"));
		assertThat(queryOptional.isPresent()).isTrue();
		assertThat(queryOptional.get().getAttr().get(0)).isEqualTo("HeapMemoryUsage");
	}

	private static class ByObj implements Predicate<Query> {

		private final ObjectName obj;

		private ByObj(String obj) throws MalformedObjectNameException {
			this.obj = new ObjectName(obj);
		}

		@Override
		public boolean apply(@Nullable Query query) {
			return query.getObjectName().equals(this.obj);}
	}
}
