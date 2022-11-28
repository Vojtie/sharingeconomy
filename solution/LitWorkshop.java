import java.util.*;
import java.util.concurrent.*;

public class LitWorkshop implements Workshop {

    private int n;

    private final Map<Long, WorkplaceId> userToWid = new HashMap<>();

    private final Map<WorkplaceId, int[]> counters = new HashMap<>(); // [] = {enterers, switchers, users}

    private final Map<Long, Semaphore> users = new ConcurrentHashMap<>();

    private final Map<Long, Object[]> userInfo = new ConcurrentHashMap<>(); // [] == {enter/switch, wants/prev, curr, isWaiting}

    private final Map<WorkplaceId, Occupation> occupation = new HashMap<>();

    private final Map<WorkplaceId, Long> useWaiters = new HashMap<>();

    private final Map<WorkplaceId, Workplace> workplaces = new ConcurrentHashMap<>();

    private WantingQueue wanting;

    private final Semaphore mutex = new Semaphore(1, true);

    LitWorkshop(Collection<Workplace> workplaces) {
        n = workplaces.size();
        for (var workplace : workplaces) {
            this.workplaces.put(workplace.getId(), new WorkplaceGuard(workplace));
            occupation.put(
                    workplace.getId(),
                    Occupation.FREE
            );
        }
        this.wanting = new WantingQueue(n);
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        try {
            mutex.acquire();
            userInfo.put(getMyId(), new Object[]{"enter", wid, null, false}); // action, wantedWid, curWid, isWaiting
            wanting.add(getMyId());

            if (!canEnter() || occupation.get(wid) != Occupation.FREE) {
                assert wanting.getLongestPassCount() == (2L * n) - 2;
                if (!canEnter()) {
                    wanting.addBlocked(getMyId());
                }
                incrementEnterersCount(wid);
                setIsWaiting(true);
                mutex.release();
                users.get(getMyId()).acquire(); // brak dsk
            } else {
                occupation.put(wid, Occupation.WORKING);
                wanting.pass(getMyId());
                userInfo.get(getMyId())[3] = false;
                userInfo.get(getMyId())[2] = wid;
                userInfo.get(getMyId())[1] = null;
                mutex.release();
            }
            return workplaces.get(wid);
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    public void use() {
        // wake up if someone was blocked by me
        WorkplaceId wid = new WorkplaceId() {
            @Override
            public int compareTo(WorkplaceId o) {
                return 0;
            }
        }; // this wid
        try {
            // state is set
            this.mutex.acquire();
            if (getWantedAction(getMyId()).equals("switch")) {
                var prevWid = getWantedWorkplaceId(getMyId()); // previous
                if (occupation.get(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert occupation.get(prevWid) == Occupation.ONE_OCCUPYING;
                    if (getSwitchersCount(prevWid) > 0) {
                        wakeUpAndSet(prevWid, "switch");
                    } else if (getEnterersCount(wid) > 0 && canEnter(wid)) {
                        wakeUpAndSet(prevWid, "enter");
                    } else if (getEnterersCount(wid) > 0) {
                        // cant enter
                        wanting.addBlocked(findFirstWantingToEnter(wid));
                    }
                }
            }
            if (wanting.didUserBlock(getMyId())) {

            }


//            if (occupation.get(wid) == Occupation.TWO_OCCUPYING) {
//                incrementUsersCount(wid);
//                mutex.release();
//                // leave info that u sleep here
//                useWaiters.put(wid, getMyId());
//                users.get(getMyId()).acquire();
//            } else {
//                assert occupation.get(wid) == Occupation.WORKING;
//            }
//            if (getWantedAction(getMyId()).equals("switch")) {
//                if (getUsersCount(prevWid) > 0) {
//                    assert getUsersCount(prevWid) == 1;
//                    users.get(useWaiters.get(prevWid)).release();
//                } else if (getSwitchersCount(prevWid) > 0) {
//                    wakeUp(prevWid, "switch");
//                } else if (canEnter() && getEnterersCount(prevWid) > 0) {
//                    wakeUp(prevWid, "enter");
//                }
//            }
            setCurrentWorkplaceId(wid);
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutex.acquire();
            wanting.add(getMyId());
            setWantedAction("switch");
            setWantedWorkplaceId(wid);
            occupation.put(getCurrentWorkplaceId(), Occupation.ONE_OCCUPYING); // set previous
            if (occupation.get(wid) == Occupation.WORKING || occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                incrementSwitchersCount(wid);
                setIsWaiting(true);
                mutex.release();
                users.get(getMyId()).acquire(); // woke up by leave() or use() on other wid
//                occupation.put(wid, Occupation.WORKING);
//                setIsWaiting(false);
//                decrementSwitchersCount(wid);
            } else {
                setWantedWorkplaceId(getCurrentWorkplaceId()); // remember previous wid on wanted
                setCurrentWorkplaceId(wid);
                setIsWaiting(false);
                if (occupation.get(wid) == Occupation.ONE_OCCUPYING) {
                    occupation.put(wid, Occupation.TWO_OCCUPYING);
                    useWaiters.put(wid, getMyId());
                } else {
                    assert occupation.get(wid) == Occupation.FREE;
                    occupation.put(wid, Occupation.WORKING);
                }
                mutex.release();
            }

            return workplaces.get(wid);
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    @Override
    public void leave() {
        try {
            WorkplaceId prevWid = getCurrentWorkplaceId();
            mutex.acquire();
            cleanUp();
            if (getUsersCount(prevWid) > 0) {
                throw new RuntimeException("unexpected: should only be possible in switch not leave");
            } else if (getSwitchersCount(prevWid) > 0) {
                wakeUpAndSet(prevWid, "switch");
            } else if (canEnter(prevWid) && getEnterersCount(prevWid) > 0) {
                wakeUpAndSet(prevWid, "enter");
            } else {
                if (getEnterersCount(prevWid) > 0) {
                    // cant enter
                    wanting.addBlocked(findFirstWantingToEnter(prevWid));
                }
                occupation.put(prevWid, Occupation.FREE);
            }
            mutex.release();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    private void cleanUp() {
        userToWid.remove(getMyId());
        users.remove(getMyId());
        userInfo.remove(getMyId());
        assert !wanting.remove(getMyId()); // should've been done in use()
    }

    private long getMyId() {
        return Thread.currentThread().getId();
    }

    private long findFirstWantingToEnter(WorkplaceId wid) {
        for (var user : wanting.usersToList()) {
            if (isWaiting(user) && getWantedAction(user).equals("enter")
                    && getWantedWorkplaceId(user).compareTo(wid) == 0) {
                return user;
            }
        }
        throw new RuntimeException("no wanting to enter wid despite count > 0");
    }

    private void wakeUpBlocked() {
        for (var blockedUser : wanting.getBlocked()) {

        }
    }

    private WorkplaceId getCurrentWorkplaceId() {
        assert userInfo.get(getMyId())[2] != null;
        assert userInfo.get(getMyId())[2] instanceof WorkplaceId;
        return (WorkplaceId) userInfo.get(getMyId())[2];
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
        return (boolean) userInfo.get(getMyId())[3];
    }

    private void setCurrentWorkplaceId(WorkplaceId wid) {
        userInfo.get(getMyId())[2] = wid;
    }

    private void setWantedWorkplaceId(WorkplaceId wid) {
        userInfo.get(getMyId())[1] = wid;
    }

    private void setWantedAction(String action) {
        userInfo.get(getMyId())[0] = action;
    }

    private void setIsWaiting(boolean isWaiting) {
        userInfo.get(getMyId())[3] = isWaiting;
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

    private void wakeUpAndSet(WorkplaceId wid, String action) {
        // wake up first such that isWaiting to enter to wid
        assert action.equals("switch") || action.equals("enter");
        for (var wantingId : wanting.usersToList()) {
            if (isWaiting(wantingId) && getWantedWorkplaceId(wantingId).compareTo(wid) == 0
                    && getWantedAction(wantingId).equals(action)) {
                occupation.put(wid, Occupation.WORKING);
                if (action.equals("switch")) {
                    userInfo.get(wantingId)[1] = userInfo.get(wantingId)[2];
                    decrementSwitchersCount(wid);
                } else if (action.equals("enter")) {
                    wanting.pass(wantingId);
                    userInfo.get(wantingId)[1] = null;
                    decrementEnterersCount(wid);
                }
                userInfo.get(wantingId)[2] = wid;
                userInfo.get(wantingId)[3] = false;
                users.get(wantingId).release();
                return;
            }
        }
        throw new RuntimeException("unexpected error: no one to be woken up");
        // assert false;
    }

    private void wakeUpUseWaiterAndSet(WorkplaceId wid) {
        assert useWaiters.get(wid).compareTo(null) != 0;
        var waiting = useWaiters.get(wid);
        occupation.put(wid, Occupation.WORKING);
        decrementUsersCount(wid);
        userInfo.get(waiting)[2] = wid;
        userInfo.get(waiting)[3] = false;
        useWaiters.remove(wid);
        users.get(waiting).release();
    }

    // checks whether new user can enter the workshop
    // (doesn't violate the 2*N condition)
    private boolean canEnter(WorkplaceId wid) {
        var longestWanting = wanting.getLongestWaiting();
        return wanting.getLongestPassCount() < (2L * n) - 2
                || (isWaiting(longestWanting) && getWantedWorkplaceId(longestWanting).compareTo(wid) == 0
                    && getWantedAction(longestWanting).equals("enter"));
    }

    public static void main(String[] args) {
        
    }
}
