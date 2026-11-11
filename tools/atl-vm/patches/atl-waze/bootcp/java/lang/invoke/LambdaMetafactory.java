/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 */

package java.lang.invoke;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Methods to facilitate the creation of simple "function objects" that implement one or more
 * interfaces by delegation to a provided {@link MethodHandle}, after appropriate type adaptation and
 * partial evaluation of arguments (the OpenJDK API; this is the bootstrap method javac names for
 * every lambda expression and method reference).
 *
 * Android's libcore ships this class only as a placeholder because D8 desugars lambdas at build
 * time; the ART boot classes in this ATL build were dexed without that step, so every lambda inside
 * libcore (e.g. {@code DoubleUnaryOperator.andThen}, {@code Comparator.comparing}) reaches this
 * bootstrap. OpenJDK spins a hidden class per call site; without a runtime class generator, the
 * function object here is a {@link Proxy} of the functional interface whose handler forwards the
 * single abstract method (and the bridges) to the implementation method handle with the captured
 * arguments prepended, and runs the interface's default methods through {@code invokespecial}.
 * Semantics follow the specification: capture happens once per evaluation of the call site target,
 * identity is not guaranteed, and {@link #altMetafactory} marker interfaces are added to the proxy.
 */
public final class LambdaMetafactory {
	/** Flag for alternate metafactories indicating the lambda object must be serializable */
	public static final int FLAG_SERIALIZABLE = 1 << 0;
	/** Flag for alternate metafactories indicating the lambda object implements other marker interfaces */
	public static final int FLAG_MARKERS = 1 << 1;
	/** Flag for alternate metafactories indicating the lambda object requires additional bridge methods */
	public static final int FLAG_BRIDGES = 1 << 2;

	private static final boolean DEBUG = System.getenv("ATL_DEBUG_LAMBDA") != null;
	private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
	private static final MethodType[] EMPTY_MT_ARRAY = new MethodType[0];

	private LambdaMetafactory() {
	}

	public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
	                                   MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType)
			throws LambdaConversionException {
		return buildCallSite(caller, invokedName, invokedType, samMethodType, implMethod, instantiatedMethodType,
		                     EMPTY_CLASS_ARRAY, EMPTY_MT_ARRAY);
	}

	public static CallSite altMetafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, Object... args)
			throws LambdaConversionException {
		MethodType samMethodType = (MethodType) args[0];
		MethodHandle implMethod = (MethodHandle) args[1];
		MethodType instantiatedMethodType = (MethodType) args[2];
		int flags = (Integer) args[3];
		Class<?>[] markerInterfaces;
		MethodType[] bridges;
		int argIndex = 4;
		if ((flags & FLAG_MARKERS) != 0) {
			int markerCount = (Integer) args[argIndex++];
			markerInterfaces = new Class<?>[markerCount];
			System.arraycopy(args, argIndex, markerInterfaces, 0, markerCount);
			argIndex += markerCount;
		} else {
			markerInterfaces = EMPTY_CLASS_ARRAY;
		}
		if ((flags & FLAG_BRIDGES) != 0) {
			int bridgeCount = (Integer) args[argIndex++];
			bridges = new MethodType[bridgeCount];
			System.arraycopy(args, argIndex, bridges, 0, bridgeCount);
			argIndex += bridgeCount;
		} else {
			bridges = EMPTY_MT_ARRAY;
		}
		if ((flags & FLAG_SERIALIZABLE) != 0) {
			Class<?>[] withSerializable = Arrays.copyOf(markerInterfaces, markerInterfaces.length + 1);
			withSerializable[markerInterfaces.length] = java.io.Serializable.class;
			markerInterfaces = withSerializable;
		}
		return buildCallSite(caller, invokedName, invokedType, samMethodType, implMethod, instantiatedMethodType,
		                     markerInterfaces, bridges);
	}

	private static CallSite buildCallSite(MethodHandles.Lookup caller, String invokedName, MethodType invokedType,
	                                      MethodType samMethodType, MethodHandle implMethod, MethodType instantiatedMethodType,
	                                      Class<?>[] markerInterfaces, MethodType[] bridges) throws LambdaConversionException {
		if (invokedType == null || samMethodType == null || implMethod == null || instantiatedMethodType == null)
			throw new LambdaConversionException("null argument to the metafactory");
		// MethodType objects ART materialises from the dex proto ids skip the MethodTypeForm that the
		// Java-side adapters rely on (asType/isConvertibleTo/invokeWithArguments NPE on it); give them one.
		ensureForm(invokedType);
		ensureForm(samMethodType);
		ensureForm(instantiatedMethodType);
		ensureForm(implMethod.type());
		Class<?> samBase = invokedType.returnType();
		if (!samBase.isInterface())
			throw new LambdaConversionException("Functional interface " + samBase.getName() + " is not an interface");
		for (Class<?> c : markerInterfaces) {
			if (!c.isInterface())
				throw new LambdaConversionException("Marker interface " + c.getName() + " is not an interface");
		}
		Site site = new Site(samBase, invokedName, samMethodType, implMethod, instantiatedMethodType, markerInterfaces, bridges);
		try {
			// MethodHandles.lookup() refuses lookup classes in java.lang.invoke; the package constructor does not
			MethodHandle factory = privateLookup(LambdaMetafactory.class).findStatic(LambdaMetafactory.class, "newFunctionObject",
			                                                       MethodType.methodType(Object.class, Site.class, Object[].class));
			if (DEBUG) System.err.println("[lmf] invokedType=" + invokedType + " sam=" + samMethodType + " impl=" + implMethod.type() + " inst=" + instantiatedMethodType + " f0=" + factory.type());
			factory = factory.bindTo(site);
			if (DEBUG) System.err.println("[lmf] bound=" + factory.type());
			factory = factory.asCollector(Object[].class, invokedType.parameterCount());
			if (DEBUG) System.err.println("[lmf] collected=" + factory.type());
			// ART runs the call site target with invokeExact semantics against the handle's real type;
			// asType() only records a nominal type, explicitCastArguments() builds a transformer whose
			// real type is the call site type.
			factory = MethodHandles.explicitCastArguments(factory, invokedType);
			if (DEBUG) System.err.println("[lmf] adapted=" + factory.type() + " class=" + factory.getClass().getName());
			ConstantCallSite cs = new ConstantCallSite(factory);
			if (DEBUG) System.err.println("[lmf] callsite type=" + cs.type() + " target=" + cs.getTarget().type());
			return cs;
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new LambdaConversionException("cannot build the lambda factory", e);
		}
	}

	/** Everything a call site knows about its lambda; shared by all function objects it produces. */
	private static final class Site {
		final Class<?> samBase;
		final String samName;
		final MethodType samMethodType;
		final MethodHandle implMethod;
		final MethodType instantiatedMethodType;
		final Class<?>[] interfaces;
		final MethodType[] bridges;
		final ClassLoader loader;

		Site(Class<?> samBase, String samName, MethodType samMethodType, MethodHandle implMethod,
		     MethodType instantiatedMethodType, Class<?>[] markers, MethodType[] bridges) {
			this.samBase = samBase;
			this.samName = samName;
			this.samMethodType = samMethodType;
			this.implMethod = implMethod;
			this.instantiatedMethodType = instantiatedMethodType;
			this.bridges = bridges;
			List<Class<?>> all = new ArrayList<>();
			all.add(samBase);
			for (Class<?> m : markers)
				if (!all.contains(m))
					all.add(m);
			this.interfaces = all.toArray(new Class<?>[0]);
			ClassLoader l = samBase.getClassLoader();
			this.loader = l != null ? l : LambdaMetafactory.class.getClassLoader();
		}

		/** Whether a proxied method is the functional method (or one of its bridges). */
		boolean isFunctionalMethod(Method m) {
			if (!m.getName().equals(samName))
				return false;
			int n = m.getParameterTypes().length;
			if (n == samMethodType.parameterCount())
				return true;
			for (MethodType b : bridges)
				if (n == b.parameterCount())
					return true;
			return false;
		}
	}

	/** Called through the call site target with the captured arguments: one function object. */
	private static Object newFunctionObject(Site site, Object[] captured) {
		return Proxy.newProxyInstance(site.loader, site.interfaces, new Handler(site, captured));
	}

	private static final class Handler implements InvocationHandler {
		private final Site site;
		private final Object[] captured;

		Handler(Site site, Object[] captured) {
			this.site = site;
			this.captured = captured;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (site.isFunctionalMethod(method)) {
				Object[] all;
				if (args == null || args.length == 0) {
					all = captured;
				} else if (captured.length == 0) {
					all = args;
				} else {
					all = new Object[captured.length + args.length];
					System.arraycopy(captured, 0, all, 0, captured.length);
					System.arraycopy(args, 0, all, captured.length, args.length);
				}
				return site.implMethod.invokeWithArguments(all);
			}
			Class<?> declaring = method.getDeclaringClass();
			if (declaring == Object.class) {
				switch (method.getName()) {
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					case "toString":
						return site.samBase.getName() + "$$Lambda@" + Integer.toHexString(System.identityHashCode(proxy));
					default:
						break;
				}
			}
			if (method.isDefault() || !Modifier.isAbstract(method.getModifiers())) {
				// A default method of the interface (e.g. Function.andThen): run the interface's own body
				// on this object, which is what invokespecial does for the generated lambda class.
				MethodHandles.Lookup lookup = privateLookup(declaring);
				MethodHandle target = lookup.unreflectSpecial(method, declaring).bindTo(proxy);
				return args == null ? target.invokeWithArguments() : target.invokeWithArguments(args);
			}
			throw new AbstractMethodError(method.toString());
		}
	}

	private static java.lang.reflect.Field sFormField;

	static void ensureForm(MethodType mt) {
		if (mt == null)
			return;
		try {
			java.lang.reflect.Field f = sFormField;
			if (f == null) {
				f = MethodType.class.getDeclaredField("form");
				f.setAccessible(true);
				sFormField = f;
			}
			if (f.get(mt) == null)
				f.set(mt, MethodTypeForm.findForm(mt));
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (DEBUG) System.err.println("[lmf] cannot initialise MethodTypeForm: " + e);
		}
	}

	/** A lookup with full access to the interface, needed for invokespecial of its default methods. */
	private static MethodHandles.Lookup privateLookup(Class<?> target) throws IllegalAccessException {
		try {
			java.lang.reflect.Constructor<MethodHandles.Lookup> ctor =
			    MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
			ctor.setAccessible(true);
			// PUBLIC | PRIVATE | PROTECTED | PACKAGE = 0xf
			return ctor.newInstance(target, 0xf);
		} catch (ReflectiveOperationException e) {
			throw new IllegalAccessException("cannot create a private lookup for " + target.getName() + ": " + e);
		}
	}
}
