module convex.core {
	exports convex.core;
	exports convex.core.store;
	exports convex.core.data.util;
	exports convex.core.crypto;
	exports convex.core.cpos;
	exports convex.core.data.type;
	exports convex.core.init;
	exports convex.core.cvm;
	exports convex.core.cvm.exception;
	exports convex.core.cvm.transactions;
	exports convex.core.util;
	exports convex.core.exceptions;
	exports convex.core.data.prim;
	exports convex.core.text;
	exports convex.core.lang.reader;
	exports convex.core.crypto.wallet;
	exports convex.core.cvm.ops;
	exports convex.core.data;
	exports convex.core.lang;
	exports convex.etch;
	exports convex.dlfs;

	requires transitive org.antlr.antlr4.runtime;
	requires org.bouncycastle.pkix;
	requires transitive org.bouncycastle.provider;
	requires org.bouncycastle.util;
	requires org.slf4j;
	requires java.base;
}