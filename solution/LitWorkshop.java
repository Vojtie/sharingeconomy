import java.util.*;
import java.util.concurrent.*;

public class LitWorkshop implements Workshop {

    int n;
    // private final Map<Long, WorkplaceId> userToWid = new HashMap<>();
    private final Map<WorkplaceId, int[]> counters = new HashMap<>(); // [] = {enterers, switchers}

    private final Map<Long, Semaphore> users = new ConcurrentHashMap<>();

    private final Map<Long, Object[]> userInfo = new ConcurrentHashMap<>(); // [] == {enter/switch, wants/prev, curr, isWaiting}

    private final Map<Long, Boolean> isWaiting = new HashMap<>();

    private final Map<WorkplaceId, Occupation> occupation = new HashMap<>();

    private final Map<WorkplaceId, Long> useWaiters = new HashMap<>();

    private final Map<WorkplaceId, Workplace> workplaces = new ConcurrentHashMap<>();

    private final WantingQueue wanting;

    private final Semaphore mutex = new Semaphore(1, true);

    LitWorkshop(Collection<Workplace> workplaces) {
        n = workplaces.size();
        for (var workplace : workplaces) {
            var wid = workplace.getId();
            this.workplaces.put(wid, new WorkplaceGuard(workplace, this));
            counters.put(
                    wid,
                    new int[]{0, 0}
            );
            occupation.put(
                    wid,
                    Occupation.FREE
            );
        }
        this.wanting = new WantingQueue(n);
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        try {
            mutex.acquire();
            users.put(getMyId(), new Semaphore(0));
            isWaiting.put(getMyId(), false);
            userInfo.put(getMyId(), new Object[]{"enter", wid, null, false}); // action, wantedWid, curWid, isWaiting
            wanting.add(getMyId());

            if (!canEnter(wid) || occupation.get(wid) != Occupation.FREE) {
                assert wanting.getLongestPassCount() == (2L * n) - 2;
                if (!canEnter(wid)) {
                    wanting.addBlocked(getMyId());
                }
                incrementEnterersCount(wid);
                setIsWaiting(getMyId(), true);
                isWaiting.put(getMyId(), true);
                mutex.release();
                users.get(getMyId()).acquire(); // brak dsk
            } else {
                occupation.put(wid, Occupation.WORKING);
                wanting.pass(getMyId());
                userInfo.get(getMyId())[3] = false;
                isWaiting.put(getMyId(), false);
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

    void use(WorkplaceId wid) {
        try {
            // state is set
            this.mutex.acquire();
            wanting.remove(getMyId()); // exit Queue
            if (getWantedAction(getMyId()).equals("switch")) {
                var prevWid = getWantedWorkplaceId(getMyId()); // previous
                if (occupation.get(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert occupation.get(prevWid) == Occupation.ONE_OCCUPYING;
                    if (getSwitchersCount(prevWid) > 0) {
                        wakeUpAndSet(prevWid, "switch");
                    } else if (getEnterersCount(prevWid) > 0 && canEnter(prevWid)) {
                        wakeUpAndSet(prevWid, "enter");
                    } else { // can't let in anybody, leave prev as free
                        occupation.put(prevWid, Occupation.FREE);
                        if (getEnterersCount(prevWid) > 0) {
                            assert(!canEnter(prevWid)); // can't enter
                            wanting.addBlocked(findFirstWantingToEnter(prevWid));
                        }
                    }
                }
            }
            // if i blocked s1
            if (wanting.getWhoBlocked() != null && wanting.getWhoBlocked() == getMyId()) {
                wakeUpBlocked();
            }
            // wait for the second guy to leave the workstation
            if (occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                assert useWaiters.get(wid) == null;
                assert getWantedAction(getMyId()).equals("switch");
                setIsWaiting(getMyId(), true);
                useWaiters.put(wid, getMyId());
                isWaiting.put(getMyId(), true);
                // one who wakes sets all including removing useWaiters
                users.get(getMyId()).acquire();
            } else {
                occupation.put(wid, Occupation.WORKING);
                mutex.release();
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
            //setCurrentWorkplaceId(wid);
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
            isWaiting.put(getMyId(), false);
            setWantedAction("switch");
            setWantedWorkplaceId(wid);
            occupation.put(getCurrentWorkplaceId(), Occupation.ONE_OCCUPYING); // set previous
            if (occupation.get(wid) == Occupation.WORKING || occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                incrementSwitchersCount(wid);
                setIsWaiting(getMyId(), true);
                isWaiting.put(getMyId(), true);
                System.out.println("thread waiting to switch: " + getMyId() + " set is waiting to true: " + isWaiting(getMyId()));
                mutex.release();
                users.get(getMyId()).acquire(); // woke up by leave() or use() on other wid
//                occupation.put(wid, Occupation.WORKING);
//                setIsWaiting(false);
//                decrementSwitchersCount(wid);
            } else {
                setWantedWorkplaceId(getCurrentWorkplaceId()); // remember previous wid on wanted
                setCurrentWorkplaceId(wid);
                //setIsWaiting(getMyId(), false);
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
            if (useWaiters.get(prevWid) != null) {
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

     void cleanUp() {
        //userToWid.remove(getMyId());
        users.remove(getMyId());
        userInfo.remove(getMyId());
        assert !wanting.remove(getMyId()); // should've been done in use()
    }

     long getMyId() {
        return Thread.currentThread().getId();
    }

     long findFirstWantingToEnter(WorkplaceId wid) {
        for (var user : wanting.usersToList()) {
            if (isWaiting(user) && getWantedAction(user).equals("enter")
                    && getWantedWorkplaceId(user).compareTo(wid) == 0) {
                return user;
            }
        }
        throw new RuntimeException("no wanting to enter wid despite count > 0");
    }

     void wakeUpBlocked() {
        var blocked = wanting.getBlocked();
        var freed = new ArrayList<Long>();
        for (var blockedUser : blocked) {
            assert getWantedAction(blockedUser).equals("enter");
            assert isWaiting(blockedUser);
            var wantedWid = getWantedWorkplaceId(blockedUser);
            if (!canEnter(wantedWid)) { // still blocked
                assert wanting.getLongestWaiting() != blockedUser;
                assert wanting.getLongestPassCount() == (2L * n) - 2;
                break; // the rest still blocked too
            } else {
                // waiting for workplace or entering, not blocked anymore
                freed.add(blockedUser);
                if (occupation.get(wantedWid) == Occupation.FREE) {
                    occupation.put(wantedWid, Occupation.WORKING);
                    wanting.pass(blockedUser);
                    userInfo.get(blockedUser)[1] = null;
                    userInfo.get(blockedUser)[2] = wantedWid;
                    setIsWaiting(blockedUser, false);
                    isWaiting.put(getMyId(), false);
                    decrementEnterersCount(wantedWid);
                    users.get(blockedUser).release(); // let in
                }
            }
        }
        wanting.updateWhoBlocked(); // set who blocked
        blocked.removeAll(freed); // remove from blocked those that are not blocked anymore
    }

     WorkplaceId getCurrentWorkplaceId() {
        assert userInfo.get(getMyId())[2] != null;
        assert userInfo.get(getMyId())[2] instanceof WorkplaceId;
        return (WorkplaceId) userInfo.get(getMyId())[2];
    }

     WorkplaceId getWantedWorkplaceId(Long user) {
        assert userInfo.get(user)[1] != null;
        assert userInfo.get(user)[1] instanceof WorkplaceId;
        return (WorkplaceId) userInfo.get(user)[1];
    }

     String getWantedAction(long user) {
        assert userInfo.get(user)[0] != null;
        assert userInfo.get(user)[0] instanceof String;
        return (String) userInfo.get(user)[0];
    }

     boolean isWaiting(long user) {
//        assert userInfo.get(user)[3] != null;
//        assert userInfo.get(user)[3] instanceof Boolean;
//        return (Boolean) userInfo.get(getMyId())[3];
         return isWaiting.get(user);
    }

     void setCurrentWorkplaceId(WorkplaceId wid) {
        userInfo.get(getMyId())[2] = wid;
    }

     void setWantedWorkplaceId(WorkplaceId wid) {
        userInfo.get(getMyId())[1] = wid;
    }

     void setWantedAction(String action) {
        userInfo.get(getMyId())[0] = action;
    }

     void setIsWaiting(long user, boolean isWaiting) {
        userInfo.get(user)[3] = isWaiting;
        System.out.println(user + "  " + isWaiting(user) + " == " + isWaiting);
//        var info = userInfo.get(getMyId());
//        userInfo.put(getMyId(), new Object[]{info[0], info[1], info[2], isWaiting});
    }

     int getEnterersCount(WorkplaceId wid) {
        return counters.get(wid)[0];
    }

     int getSwitchersCount(WorkplaceId wid) {
        return counters.get(wid)[1];
    }

     void incrementEnterersCount(WorkplaceId wid) {
        counters.get(wid)[0]++;
    }

     void decrementEnterersCount(WorkplaceId wid) {
        counters.get(wid)[0]--;
    }

     void incrementSwitchersCount(WorkplaceId wid) {
        counters.get(wid)[1]++;
    }

     void decrementSwitchersCount(WorkplaceId wid) {
        counters.get(wid)[1]--;
    }

//     int getUsersCount(WorkplaceId wid) {
//        return counters.get(wid)[2];
//    }

//     void incrementUsersCount(WorkplaceId wid) {
//        assert counters.get(wid)[2] == 0;
//        counters.get(wid)[2] = 1;
//    }
//
//     void decrementUsersCount(WorkplaceId wid) {
//        assert counters.get(wid)[2] == 1;
//        counters.get(wid)[2] = 0;
//    }

     void wakeUpAndSet(WorkplaceId wid, String action) {
        // wake up first such that isWaiting to enter to wid
        assert action.equals("switch") || action.equals("enter");
        var wantingUsers = wanting.usersToList();
        System.err.println("wanting que size: " + wantingUsers.size());
        for (var wantingId : wantingUsers) {
            System.err.println("wanting user: " + wantingId + " wanted action:" + getWantedAction(wantingId)
                    + " isWaiting: " + isWaiting(wantingId) + " wanted workplaceId: " + getWantedWorkplaceId(wantingId));
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
                setIsWaiting(wantingId, false);
                isWaiting.put(getMyId(), false);
                users.get(wantingId).release();
                return;
            }
        }
        throw new RuntimeException("unexpected error: no one to be woken up");
        // assert false;
    }

     void wakeUpUseWaiterAndSet(WorkplaceId wid) {
        assert useWaiters.get(wid) != null;
        var waiting = useWaiters.get(wid);
        occupation.put(wid, Occupation.WORKING);
        //decrementUsersCount(wid);
        userInfo.get(waiting)[2] = wid;
        setIsWaiting(waiting, false);
        isWaiting.put(getMyId(), false);
        useWaiters.remove(wid);
        users.get(waiting).release();
    }

    // checks whether new user can enter the workshop
    // (doesn't violate the 2*N condition)
     boolean canEnter(WorkplaceId wid) {
        if (wanting.empty())
            return true;
        var longestWanting = wanting.getLongestWaiting();
        return wanting.getLongestPassCount() < (2L * n) - 2
                || (isWaiting(longestWanting) && getWantedWorkplaceId(longestWanting).compareTo(wid) == 0
                    && getWantedAction(longestWanting).equals("enter"));
    }

    public static void main(String[] args) {
        
    }
}
