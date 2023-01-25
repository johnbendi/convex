package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

public class BigIntegerTest {

	@Test public void testBigIntegerAssumptions() {
		assertThrows(java.lang.NumberFormatException.class,()->new BigInteger(new byte[0]));
		assertEquals(BigInteger.ZERO,new BigInteger(new byte[1]));
	}
	
	@Test public void testZero() {
		CVMBigInteger bi=CVMBigInteger.create(new byte[] {0});
		assertEquals(0,bi.longValue());
		assertEquals(0.0,bi.doubleValue());
		assertEquals(BigInteger.ZERO,bi.getBigInteger());
		
		doBigTest(bi);
	}
	
	@Test public void testOne() {
		CVMBigInteger bi=CVMBigInteger.create(new byte[] {1});
		assertEquals(1,bi.longValue());
		assertEquals(1.0,bi.doubleValue());
		assertEquals(BigInteger.ONE,bi.getBigInteger());
		
		doBigTest(bi);
	}
	
	@Test public void testSmallestPositive() {
		CVMBigInteger bi=CVMBigInteger.create(new byte[] {0,-128,0,0,0,0,0,0,0});
		assertEquals(Long.MIN_VALUE,bi.longValue());
		
		// Extra leading zeros should get ignored
		assertEquals(bi,CVMBigInteger.create(new byte[] {0,0,0,-128,0,0,0,0,0,0,0}));
		
		doBigTest(bi);
	}

	private void doBigTest(CVMBigInteger bi) {
		// BigInteger value should be cached
		BigInteger big=bi.getBigInteger();
		assertSame(big,bi.getBigInteger());
		
		CVMBigInteger bi2=CVMBigInteger.create(big);
		assertEquals(bi.getEncoding(),bi2.getEncoding());
		assertEquals(bi,bi2);
	}
}