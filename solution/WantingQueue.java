import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class WantingQueue {

    private final List<long[]> userPassCount = new ArrayList<>(); // long[] == {tid, passCount}

    void add(long user) {
        userPassCount.add(new long[]{user, 0L});
    }

    boolean remove(long user) {
        return userPassCount.removeIf(x -> x[0] == user);
    }

    void increasePassCount(long user) {
        var tbc = userPassCount.stream()
                .filter(x -> (x[0] == user))
                .findFirst()
                .orElse(new long[]{-1L, 0L});
        assert tbc[0] != -1L;
        tbc[1]++;
    }

    void pass(long user) {
        for (var other : userPassCount) {
            if (other[0] == user) {
                break;
            } else {
                other[1]++;
            }
        }
    }

    long getLongestPassCount() {
        assert userPassCount.size() > 0;
        return userPassCount.get(0)[1];
    }

    List<Long> usersToList() {
        return userPassCount.stream().map(x -> x[0]).collect(Collectors.toList());
    }
}
