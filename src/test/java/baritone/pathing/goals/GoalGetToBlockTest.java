package baritone.pathing.goals;

import baritone.api.pathing.goals.GoalGetToBlock;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;

import static org.junit.Assert.assertTrue;

public class GoalGetToBlockTest {

    @Test
    public void isInGoal() {
        List<String> acceptableOffsets = new ArrayList<>(Arrays.asList("0,0,0", "0,0,1", "0,0,-1", "1,0,0", "-1,0,0", "0,-1,1", "0,-1,-1", "1,-1,0", "-1,-1,0", "0,1,0", "0,-1,0", "0,-2,0"));
        for (int x = -10; x <= 10; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -10; z <= 10; z++) {
                    boolean inGoal = new GoalGetToBlock(new BlockPos(0, 0, 0)).isInGoal(new BlockPos(x, y, z));
                    String repr = x + "," + y + "," + z;
                    System.out.println(repr + " " + inGoal);
                    if (inGoal) {
                        assertTrue(repr, acceptableOffsets.contains(repr));
                        acceptableOffsets.remove(repr);
                    }
                }
            }
        }
        assertTrue(acceptableOffsets.toString(), acceptableOffsets.isEmpty());
    }
}
