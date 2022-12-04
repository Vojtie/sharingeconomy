package cp2022.solution;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class WantingQueue {

    private final List<long[]> userPassCount = new ArrayList<>(); // long[] == {tid, passCount}

    private final List<Long> blocked = new ArrayList<>();

    private Long oneWhoBlocked = null;

    private final int n;

    WantingQueue(int n) {
        this.n = n;
    }

    void add(long user) {
        userPassCount.add(new long[]{user, 0L});
    }

    boolean remove(long user) {
        return userPassCount.removeIf(x -> x[0] == user);
    }

    void pass(long user) {
        for (var other : userPassCount) {
            if (other[0] == user) {
                break;
            } else {
                other[1]++;
                assert other[1] < 2L * n;
            }
        }
    }

    long getLongestPassCount() {
        assert userPassCount.size() > 0;
        return userPassCount.get(0)[1];
    }

    long getLongestWaiting() {
        assert userPassCount.size() > 0;
        return userPassCount.get(0)[0];
    }

    boolean empty() {
        return userPassCount.isEmpty();
    }

    Long getWhoBlocked() {
        return oneWhoBlocked;
    }

    void addBlocked(long blockedUser) {
        blocked.add(blockedUser);
        updateWhoBlocked();
    }

    void updateWhoBlocked() {
        if (blocked.size() > 0)
            oneWhoBlocked = userPassCount.get(0)[0];
        else
            oneWhoBlocked = null;
    }

    List<Long> getBlocked() {
        return blocked;
    }

    List<Long> usersToList() {
        return userPassCount.stream().map(x -> x[0]).collect(Collectors.toList());
    }

    void printBlocked() {
        System.out.print("blocked: ");
        for (var blockedUser : blocked) {
            System.out.print(blockedUser + " , ");
        }
        System.out.println();
    }
}































