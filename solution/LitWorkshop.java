import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LitWorkshop implements Workshop {

    private int n;

    private final Map<Long, WorkplaceId> userToWid = new HashMap<>();

    private final Map<WorkplaceId, int[]> counters = new HashMap<>(); // [] = {enterers, switchers, users}

    private final Map<Long, Semaphore> users = new ConcurrentHashMap<>();

    private final Map<Long, Object[]> userInfo = new ConcurrentHashMap<>(); // [] == {enter/switch, wants, isAt, isWaiting}

    private final Map<WorkplaceId, Occupation> occupation = new HashMap<>();

    private final Map<WorkplaceId, Workplace> workplaces = new ConcurrentHashMap<>();

    private final WantingQueue wanting = new WantingQueue();

    private final Semaphore mutex = new Semaphore(1, true);

    LitWorkshop(Collection<Workplace> workplaces) {
        n = workplaces.size();
        for (var workplace : workplaces) {
            this.workplaces.put(workplace.getId(), workplace);
            occupation.put(
                    workplace.getId(),
                    Occupation.FREE
            );
        }
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        try {
            mutex.acquire();
            userInfo.put(getUserId(), new Object[]{"enter", wid, null, false});
            wanting.add(getUserId());

            if (!canEnter() || occupation.get(wid) != Occupation.FREE) {
                assert wanting.getLongestPassCount() == 2L * n - 2;

                incrementEnterersCount(wid);
                setIsWaiting(true);
                mutex.release();
                users.get(getUserId()).acquire(); // dsk
                setIsWaiting(false);
                decrementEnterersCount(wid);
            }
            // state[wid] =
            assert canEnter(); assert occupation.get(wid) == Occupation.FREE || occupation.get(wid) == Occupation.ONE_OCCUPYING;
            wanting.pass(getUserId());
            // userInfo.get(getUserId())[2] = wid; wanting.remove(self)
            mutex.release();

            return new WorkplaceGuard(workplaces.get(wid));
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutex.acquire();
            wanting.add(getUserId());
            setWantedAction("switch");
            setWantedWorkplaceId(wid);
            occupation.put(getCurrentWorkplaceId(), Occupation.ONE_OCCUPYING); // set previous
            if (occupation.get(wid) == Occupation.WORKING || occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                incrementSwitchersCount(wid);
                setIsWaiting(true);
                mutex.release();
                users.get(wid).acquire(); // dsk // woke up by leave() or use() on other wid
                occupation.put(wid, Occupation.WORKING);
                setIsWaiting(false);
                decrementSwitchersCount(wid);
            } else if (occupation.get(wid) == Occupation.ONE_OCCUPYING) {
                occupation.put(wid, Occupation.TWO_OCCUPYING);
            }
            mutex.release();

            return new WorkplaceGuard(workplaces.get(wid));
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public void leave() {
        try {
            WorkplaceId wid = getCurrentWorkplaceId();
            mutex.acquire();
            cleanUp();
            if (getUsersCount(wid) > 0) {
                assert getUsersCount(wid) == 1;
                users.get(wid).release();
            } else if (getSwitchersCount(wid) > 0) {
                wakeUp(wid, "switch");
            } else if (canEnter() && getEnterersCount(wid) > 0) {
                wakeUp(wid, "enter");
            } else {
                occupation.put(wid, Occupation.FREE);
                mutex.release();
            }
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    private void cleanUp() {
        userToWid.remove(getUserId());
        users.remove(getUserId());
        userInfo.remove(getUserId());
        assert !wanting.remove(getUserId()); // should've been done in use()
    }

    private long getUserId() {
        return Thread.currentThread().getId();
    }

    private WorkplaceId getCurrentWorkplaceId() {
        assert userInfo.get(getUserId())[2] != null;
        assert userInfo.get(getUserId())[2] instanceof WorkplaceId;
        return (WorkplaceId) userInfo.get(getUserId())[2];
    }

    private WorkplaceId getWantedWorkplaceId(Long user) {
        assert userInfo.get(user)[1] != null;
        assert userInfo.get(user)[1] instanceof WorkplaceId;
        return (WorkplaceId) userInfo.get(user)[1];
    }

    private String getWantedAction(long user) {
        assert userInfo.get(user)[0] != null;
        assert userInfo.get(user)[0] instanceof String;
        return (String) userInfo.get(user)[0];
    }

    private boolean isWaiting(long user) {
        assert userInfo.get(user)[3] != null;
        assert userInfo.get(user)[3] instanceof Boolean;
        return (boolean) userInfo.get(getUserId())[3];
    }

    private void setCurrentWorkplaceId(WorkplaceId wid) {
        userInfo.get(getUserId())[2] = wid;
    }

    private void setWantedWorkplaceId(WorkplaceId wid) {
        userInfo.get(getUserId())[1] = wid;
    }

    private void setWantedAction(String action) {
        userInfo.get(getUserId())[0] = action;
    }

    private void setIsWaiting(boolean isWaiting) {
        userInfo.get(getUserId())[3] = isWaiting;
    }

    private int getEnterersCount(WorkplaceId wid) {
        return counters.get(wid)[0];
    }

    private int getSwitchersCount(WorkplaceId wid) {
        return counters.get(wid)[1];
    }

    private void incrementEnterersCount(WorkplaceId wid) {
        counters.get(wid)[0]++;
    }

    private void decrementEnterersCount(WorkplaceId wid) {
        counters.get(wid)[0]--;
    }

    private void incrementSwitchersCount(WorkplaceId wid) {
        counters.get(wid)[1]++;
    }

    private void decrementSwitchersCount(WorkplaceId wid) {
        counters.get(wid)[1]--;
    }

    private int getUsersCount(WorkplaceId wid) {
        return counters.get(wid)[2];
    }

    private void incrementUsersCount(WorkplaceId wid) {
        assert counters.get(wid)[2] == 0;
        counters.get(wid)[2] = 1;
    }

    private void decrementUsersCount(WorkplaceId wid) {
        assert counters.get(wid)[2] == 1;
        counters.get(wid)[2] = 0;
    }

    private void wakeUp(WorkplaceId wid, String action) {
        // wake up first such that isWaiting to enter to wid
        for (var wantingId : wanting.usersToList()) {
            if (isWaiting(wantingId) && getWantedWorkplaceId(wantingId) == wid
                    && getWantedAction(wantingId).equals(action)) {
                users.get(wantingId).release();
                return;
            }
        }
        throw new RuntimeException("unexpected error: no one to be woken up");
        // assert false;
    }

    // checks whether new user can enter the workshop
    // (doesn't violate the 2*N condition)
    private boolean canEnter() {
        return wanting.getLongestPassCount() < (2L * n) - 2;
    }

    public static void main(String[] args) {
        
    }
}
