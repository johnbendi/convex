package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Constants;
import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.init.InitTest;
import convex.core.lang.TestState;

/**
 * Generator for records, might not be CVM Values
 *
 */
@SuppressWarnings("rawtypes")
public class AnyRecordGen extends Generator<ARecord> {
	public AnyRecordGen() {
		super(ARecord.class);
	}

	@Override
	public ARecord generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 8) {
		case 1: {
			ACell v1= gen().make(ValueGen.class).generate(r, status);
			ACell v2= gen().make(ValueGen.class).generate(r, status);
			return Result.create(v1, v2);
		}
		case 2:
			return Belief.createSingleOrder(InitTest.HERO_KEYPAIR);
		case 3:
			return TestState.STATE;
		default:
			return Block.of(Constants.INITIAL_TIMESTAMP);
		}
	}
}
