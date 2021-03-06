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
package com.googlecode.jmxtrans.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.jmxtrans.model.naming.typename.PrependingTypeNameValuesStringBuilder;
import com.googlecode.jmxtrans.model.naming.typename.TypeNameValuesStringBuilder;
import com.googlecode.jmxtrans.model.naming.typename.UseAllTypeNameValuesStringBuilder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.IOException;
import java.rmi.UnmarshalException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.fasterxml.jackson.databind.annotation.JsonSerialize.Inclusion.NON_NULL;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static com.googlecode.jmxtrans.model.PropertyResolver.resolveList;
import static java.util.Arrays.asList;

/**
 * Represents a JMX Query to ask for obj, attr and one or more keys.
 *
 * @author jon
 */
@JsonSerialize(include = NON_NULL)
@JsonPropertyOrder(value = {"obj", "attr", "typeNames", "resultAlias", "keys", "allowDottedKeys", "useAllTypeNames", "outputWriters"})
@ThreadSafe
@ToString(exclude = {"outputWriters", "typeNameValuesStringBuilder"})
public class Query {

	private static final Logger logger = LoggerFactory.getLogger(Query.class);

	/** The JMX object representation: java.lang:type=Memory */
	@Nonnull @Getter private final ObjectName objectName;
	@Nonnull @Getter private final ImmutableList<String> keys;

	@Nonnull @Getter private final ImmutableList<String> attr;

	/**
	 * The list of type names used in a JMX bean string when querying with a
	 * wildcard which is used to expose the actual type name value to the key
	 * string. e.g. for this JMX name
	 * <p/>
	 * typeName=name=PS Eden Space,type=MemoryPool
	 * <p/>
	 * If you add a typeName("name"), then it'll retrieve 'PS Eden Space' from
	 * the string
	 */
	@Getter private final ImmutableSet<String> typeNames;

	/**
	 * The alias allows you to specify what you would like the results of the
	 * query to go into.
	 */
	@Getter private final String resultAlias;

	/**
	 * The useObjDomainAsKey property allows you to specify the use of the Domain portion of the Object Name
	 * as part of the output key instead of using the ClassName of the MBean which is the default behavior.
	 */
	@Getter private final boolean useObjDomainAsKey;
	@Getter private final boolean allowDottedKeys;
	@Getter private final boolean useAllTypeNames;
	@Nonnull @Getter private final ImmutableList<OutputWriterFactory> outputWriters;
	@Nonnull @Getter private final Iterable<OutputWriter> outputWriterInstances;
	private final TypeNameValuesStringBuilder typeNameValuesStringBuilder;

	@JsonCreator
	public Query(
			@JsonProperty("obj") String obj,
			@JsonProperty("keys") List<String> keys,
			@JsonProperty("attr") List<String> attr,
			@JsonProperty("typeNames") Set<String> typeNames,
			@JsonProperty("resultAlias") String resultAlias,
			@JsonProperty("useObjDomainAsKey") boolean useObjDomainAsKey,
			@JsonProperty("allowDottedKeys") boolean allowDottedKeys,
			@JsonProperty("useAllTypeNames") boolean useAllTypeNames,
			@JsonProperty("outputWriters") List<OutputWriterFactory> outputWriters
	) {
		try {
			this.objectName = new ObjectName(obj);
		} catch (MalformedObjectNameException e) {
			throw new IllegalArgumentException("Invalid object name: " + obj);
		}
		this.attr = resolveList(firstNonNull(attr, Collections.<String>emptyList()));
		this.resultAlias = resultAlias;
		this.useObjDomainAsKey = firstNonNull(useObjDomainAsKey, false);
		this.keys = resolveList(firstNonNull(keys, Collections.<String>emptyList()));
		this.allowDottedKeys = allowDottedKeys;
		this.useAllTypeNames = useAllTypeNames;
		this.outputWriters = outputWriters == null ? ImmutableList.<OutputWriterFactory>of() : ImmutableList.copyOf(outputWriters);
		this.typeNames = ImmutableSet.copyOf(firstNonNull(typeNames, Collections.<String>emptySet()));

		this.typeNameValuesStringBuilder = makeTypeNameValuesStringBuilder();

		this.outputWriterInstances = createOutputWriters(outputWriters);
	}

	private ImmutableList<OutputWriter> createOutputWriters(Iterable<OutputWriterFactory> outputWriters) {
		if (outputWriters == null) return ImmutableList.of();
		return FluentIterable
				.from(outputWriters)
				.transform(new Function<OutputWriterFactory, OutputWriter>() {
					@Nullable
					@Override
					public OutputWriter apply(OutputWriterFactory input) {
						return input.create();
					}
				})
				.toList();
	}

	public String makeTypeNameValueString(List<String> typeNames, String typeNameStr) {
		return this.typeNameValuesStringBuilder.build(typeNames, typeNameStr);
	}

	public Iterable<ObjectName> queryNames(MBeanServerConnection mbeanServer) throws IOException {
		return mbeanServer.queryNames(objectName, null);
	}

	public Iterable<Result> fetchResults(MBeanServerConnection mbeanServer, ObjectName queryName) throws InstanceNotFoundException, IntrospectionException, ReflectionException, IOException {
		MBeanInfo info = mbeanServer.getMBeanInfo(queryName);
		ObjectInstance oi = mbeanServer.getObjectInstance(queryName);

		List<String> attributes;
		if (attr.isEmpty()) {
			attributes = new ArrayList<String>();
			for (MBeanAttributeInfo attrInfo : info.getAttributes()) {
				attributes.add(attrInfo.getName());
			}
		} else {
			attributes = attr;
		}

		try {
			if (!attributes.isEmpty()) {
				logger.debug("Executing queryName [{}] from query [{}]", queryName.getCanonicalName(), this);

				AttributeList al = mbeanServer.getAttributes(queryName, attributes.toArray(new String[attributes.size()]));

				return new JmxResultProcessor(this, oi, al.asList(), info.getClassName(), queryName.getDomain()).getResults();
			}
		} catch (UnmarshalException ue) {
			if ((ue.getCause() != null) && (ue.getCause() instanceof ClassNotFoundException)) {
				logger.debug("Bad unmarshall, continuing. This is probably ok and due to something like this: "
						+ "http://ehcache.org/xref/net/sf/ehcache/distribution/RMICacheManagerPeerListener.html#52", ue.getMessage());
			} else {
				throw ue;
			}
		}
		return ImmutableList.of();
	}


	@Override
	public boolean equals(Object o) {
		if (o == null) {
			return false;
		}
		if (o == this) {
			return true;
		}
		if (o.getClass() != this.getClass()) {
			return false;
		}

		if (!(o instanceof Query)) {
			return false;
		}

		Query other = (Query) o;

		return new EqualsBuilder()
				.append(this.getObjectName(), other.getObjectName())
				.append(this.getKeys(), other.getKeys())
				.append(this.getAttr(), other.getAttr())
				.append(this.getResultAlias(), other.getResultAlias())
				.append(sizeOf(this.getOutputWriters()), sizeOf(other.getOutputWriters()))
				.isEquals();
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder(41, 97)
				.append(this.getObjectName())
				.append(this.getKeys())
				.append(this.getAttr())
				.append(this.getResultAlias())
				.append(sizeOf(this.getOutputWriters()))
				.toHashCode();
	}

	private static int sizeOf(List<?> writers) {
		if (writers == null) {
			return 0;
		}
		return writers.size();
	}

	private TypeNameValuesStringBuilder makeTypeNameValuesStringBuilder() {
		String separator = isAllowDottedKeys() ? "." : TypeNameValuesStringBuilder.DEFAULT_SEPARATOR;
		Set<String> typeNames = getTypeNames();
		if (isUseAllTypeNames()) {
			return new UseAllTypeNameValuesStringBuilder(separator);
		} else if (typeNames != null && !typeNames.isEmpty()) {
			return new PrependingTypeNameValuesStringBuilder(separator, new ArrayList<String>(typeNames));
		} else {
			return new TypeNameValuesStringBuilder(separator);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public void runOutputWritersForQuery(Server server, Iterable<Result> results) throws Exception {
		for (OutputWriter writer : getOutputWriterInstances()) {
			writer.doWrite(server, this, results);
		}
		logger.debug("Finished running outputWriters for query: {}", this);
	}

	@NotThreadSafe
	@Accessors(chain = true)
	public static final class Builder {
		@Setter private String obj;
		private final List<String> attr = newArrayList();
		@Setter private String resultAlias;
		private final List<String> keys = newArrayList();
		@Setter private boolean useObjDomainAsKey;
		@Setter private boolean allowDottedKeys;
		@Setter private boolean useAllTypeNames;
		private final List<OutputWriterFactory> outputWriters = newArrayList();
		private final Set<String> typeNames = newHashSet();

		private Builder() {}

		public Builder addAttr(String... attr) {
			this.attr.addAll(asList(attr));
			return this;
		}

		public Builder addKey(String keys) {
			return addKeys(keys);
		}

		public Builder addKeys(String... keys) {
			this.keys.addAll(asList(keys));
			return this;
		}

		public Builder addOutputWriter(OutputWriterFactory outputWriter) {
			return addOutputWriters(outputWriter);
		}

		public Builder addOutputWriters(OutputWriterFactory... outputWriters) {
			this.outputWriters.addAll(asList(outputWriters));
			return this;
		}

		public Builder setTypeNames(Set<String> typeNames) {
			this.typeNames.addAll(typeNames);
			return this;
		}

		public Query build() {
			return new Query(
					this.obj,
					this.keys,
					this.attr,
					this.typeNames,
					this.resultAlias,
					this.useObjDomainAsKey,
					this.allowDottedKeys,
					this.useAllTypeNames,
					this.outputWriters
			);
		}

	}
}
