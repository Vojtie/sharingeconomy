import java.util.*;
import java.util.concurrent.*;

public class LitWorkshop implements Workshop {

    int n;
    // private final Map<Long, WorkplaceId> userToWid = new HashMap<>();
    private final Map<WorkplaceId, int[]> counters = new HashMap<>(); // [] = {enterers, switchers}

    private final Map<Long, Semaphore> users = new ConcurrentHashMap<>();

    //private final Map<Long, Object[]> userInfo = new HashMap<>(); // [] == {enter/switch, wants/prev, curr, isWaiting}
    private final Map<Long, Action> actions = new HashMap<>();
    private final Map<Long, WorkplaceId> wantedWids = new HashMap<>();
    private final Map<Long, WorkplaceId> previousWorkplaceIds = new HashMap<>();
    private final Map<Long, WorkplaceId> currentWorkplaceIds = new HashMap<>();
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
            var myId = getMyId();
            users.put(myId, new Semaphore(0));
            setIsWaiting(myId, false);
            setAction(myId, Action.ENTER);
            setWantedWorkplaceId(myId, wid);
            //userInfo.put(myId, new Object[]{"enter", wid, null, false}); // action, wantedWid, curWid, isWaiting
            wanting.add(myId);

            if (!canEnter(wid) || occupation.get(wid) != Occupation.FREE) {
                assert wanting.getLongestPassCount() == (2L * n) - 2;
                if (!canEnter(wid)) {
                    wanting.addBlocked(myId);
                }
                incrementEnterersCount(wid);
                setIsWaiting(myId, true);
                isWaiting.put(myId, true);
                mutex.release();
                users.get(myId).acquire(); // brak dsk
            } else {
                occupation.put(wid, Occupation.WORKING);
                wanting.pass(myId);
                setIsWaiting(myId, false);
                setCurrentWorkplaceId(myId, wid);
                //setWantedWorkplaceId(myId, null);
                //userInfo.get(myId)[3] = false;
                //userInfo.get(myId)[2] = wid;
                //userInfo.get(myId)[1] = null;
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
            var myId = getMyId();
            wanting.remove(myId); // exit Queue
            if (getAction(myId) == Action.SWITCH) {
                var prevWid = getPreviousWorkplaceId(myId);
                if (occupation.get(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert occupation.get(prevWid) == Occupation.ONE_OCCUPYING;
                    if (getSwitchersCount(prevWid) > 0) {
                        wakeUpAndSet(prevWid, Action.SWITCH);
                    } else if (getEnterersCount(prevWid) > 0 && canEnter(prevWid)) {
                        wakeUpAndSet(prevWid, Action.ENTER);
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
            if (wanting.getWhoBlocked() != null && wanting.getWhoBlocked() == myId) {
                wakeUpBlocked();
            }
            // wait for the second guy to leave the workstation
            if (occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                assert useWaiters.get(wid) == null;
                assert getAction(myId) == Action.SWITCH;
                useWaiters.put(wid, myId);
                setIsWaiting(myId, true);
                // one who wakes sets all including removing useWaiters
                mutex.release();
                users.get(myId).acquire();
            } else {
                occupation.put(wid, Occupation.WORKING);
                mutex.release();
            }
//            if (occupation.get(wid) == Occupation.TWO_OCCUPYING) {
//                incrementUsersCount(wid);
//                mutex.release();
//                // leave info that u sleep here
//                useWaiters.put(wid, myId);
//                users.get(myId).acquire();
//            } else {
//                assert occupation.get(wid) == Occupation.WORKING;
//            }
//            if (getAction(myId).equals("switch")) {
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
            System.out.println(getMyId() + " thread gets mutex to switch");
            var myId = getMyId();
            wanting.add(myId);
            assert !isWaiting(myId); //isWaiting.put(myId, false);
            setAction(myId, Action.SWITCH);
            setWantedWorkplaceId(myId, wid);
            setPreviousWorkplaceId(myId, getCurrentWorkplaceId(myId));
            occupation.put(getCurrentWorkplaceId(myId), Occupation.ONE_OCCUPYING); // set previous state
            if (occupation.get(wid) == Occupation.WORKING || occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                incrementSwitchersCount(wid);
                setIsWaiting(myId, true);
                isWaiting.put(myId, true);
                System.out.println("thread waiting to switch: " + myId);
                mutex.release();
                users.get(myId).acquire(); // woke up by leave() or use() on other wid
                System.out.println("thread " + myId + " switched");
//                occupation.put(wid, Occupation.WORKING);
//                setIsWaiting(false);
//                decrementSwitchersCount(wid);
            } else {
                //setWantedWorkplaceId(getCurrentWorkplaceId()); // remember previous wid on wanted
                System.out.println("thread " + myId + " switched");
                setCurrentWorkplaceId(myId, wid);
                if (occupation.get(wid) == Occupation.ONE_OCCUPYING) {
                    occupation.put(wid, Occupation.TWO_OCCUPYING);
                    useWaiters.put(wid, myId);
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
            mutex.acquire();
            var myId = getMyId();
            WorkplaceId prevWid = getCurrentWorkplaceId(myId);
            cleanUp();
            System.out.println("thread " + myId + " leaving, prevWid switchers: " + getSwitchersCount(prevWid)
                + " enterers: " + getEnterersCount(prevWid));
            if (useWaiters.get(prevWid) != null) {
                throw new RuntimeException("unexpected: should only be possible in switch not leave");
            } else if (getSwitchersCount(prevWid) > 0) {
                wakeUpAndSet(prevWid, Action.SWITCH);
            } else if (canEnter(prevWid) && getEnterersCount(prevWid) > 0) {
                wakeUpAndSet(prevWid, Action.ENTER);
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
        // userToWid.remove(getMyId());
        // userInfo.remove(myId);
        var myId = getMyId();
        users.remove(myId);
        wantedWids.remove(myId);
        previousWorkplaceIds.remove(myId);
        currentWorkplaceIds.remove(myId);
        isWaiting.remove(myId);
        assert !wanting.remove(myId); // should've been done in use()
    }

     long getMyId() {
        return Thread.currentThread().getId();
    }

     long findFirstWantingToEnter(WorkplaceId wid) {
        for (var user : wanting.usersToList()) {
            if (isWaiting(user) && getAction(user) == Action.ENTER
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
            assert getAction(blockedUser) == Action.ENTER;
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
//                    userInfo.get(blockedUser)[1] = null;
//                    userInfo.get(blockedUser)[2] = wantedWid;
                    setCurrentWorkplaceId(blockedUser, wantedWid);
                    setIsWaiting(blockedUser, false);
                    decrementEnterersCount(wantedWid);
                    users.get(blockedUser).release(); // let in
                }
            }
        }
        wanting.updateWhoBlocked(); // set who blocked
        blocked.removeAll(freed); // remove from blocked those that are not blocked anymore
    }
//
//     WorkplaceId getCurrentWorkplaceId() {
//        assert userInfo.get(getMyId())[2] != null;
//        assert userInfo.get(getMyId())[2] instanceof WorkplaceId;
//        return (WorkplaceId) userInfo.get(getMyId())[2];
//    }
//
//     WorkplaceId getWantedWorkplaceId(Long user) {
//        assert userInfo.get(user)[1] != null;
//        assert userInfo.get(user)[1] instanceof WorkplaceId;
//        return (WorkplaceId) userInfo.get(user)[1];
//    }

//     String getAction(long user) {
//        assert userInfo.get(user)[0] != null;
//        assert userInfo.get(user)[0] instanceof String;
//        return (String) userInfo.get(user)[0];
//    }

     boolean isWaiting(long user) {
//        assert userInfo.get(user)[3] != null;
//        assert userInfo.get(user)[3] instanceof Boolean;
//        return (Boolean) userInfo.get(getMyId())[3];
         return isWaiting.get(user);
    }

//     void setCurrentWorkplaceId(WorkplaceId wid) {
//        userInfo.get(getMyId())[2] = wid;
//    }
//
//     void setWantedWorkplaceId(WorkplaceId wid) {
//        userInfo.get(getMyId())[1] = wid;
//    }
//
//     void setWantedAction(String action) {
//        userInfo.get(getMyId())[0] = action;
//    }

    void setIsWaiting(long user, boolean bool) {
        isWaiting.put(user, bool);
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

    Action getAction(long user) {
        var res = actions.get(user);
        if (res == null) {
            throw new RuntimeException("null action");
        }
        return res;
    }
    
    void setAction(long user, Action action) {
        actions.put(user, action);
    }
    
    WorkplaceId getWantedWorkplaceId(long user) {
        var res = wantedWids.get(user);
        if (res == null) {
            throw new RuntimeException("null wantedWid");
        }
        if (!isWaiting(user))
            throw new RuntimeException("checked wantedWid despite not waiting");
        return res;
    }

    WorkplaceId getCurrentWorkplaceId(long user) {
        var res = currentWorkplaceIds.get(user);
        if (res == null) {
            throw new RuntimeException("null currentWorkplaceId");
        }
        return res;
    }
    
    WorkplaceId getPreviousWorkplaceId(long user) {
        var res = previousWorkplaceIds.get(user);
        if (res == null) {
            throw new RuntimeException("null previousWorkplaceId");
        }
        return res;
    }
    
    void setWantedWorkplaceId(long user, WorkplaceId wid) {
        wantedWids.put(user, wid);
    }

    void setCurrentWorkplaceId(long user, WorkplaceId wid) {
        currentWorkplaceIds.put(user, wid);
    }

    void setPreviousWorkplaceId(long user, WorkplaceId wid) {
        previousWorkplaceIds.put(user, wid);
    }

     void wakeUpAndSet(WorkplaceId wid, Action action) {
        // wake up first such that isWaiting to enter to wid
        assert action == Action.SWITCH || action == Action.ENTER;

        var wantingUsers = wanting.usersToList();
        System.out.println("wanting que size: " + wantingUsers.size());

        for (var wantingId : wantingUsers) {
            System.out.println("wanting user: " + wantingId + " | action:" + getAction(wantingId)
                    + " | isWaiting: " + isWaiting(wantingId) + " | wanted workplaceId: " + getWantedWorkplaceId(wantingId));

            if (isWaiting(wantingId) && getWantedWorkplaceId(wantingId).compareTo(wid) == 0
                    && getAction(wantingId) == action) {
                System.out.println(getMyId() + " waking " + wantingId + " action " + getAction(wantingId));
                occupation.put(wid, Occupation.WORKING);
                if (action == Action.SWITCH) {
                    //userInfo.get(wantingId)[1] = userInfo.get(wantingId)[2];
                    setPreviousWorkplaceId(wantingId, getCurrentWorkplaceId(wantingId));
                    decrementSwitchersCount(wid);
                } else {// if (action == Action.ENTER) {
                    wanting.pass(wantingId);
                    //userInfo.get(wantingId)[1] = null;
                    decrementEnterersCount(wid);
                }
                // userInfo.get(wantingId)[2] = wid;
                setCurrentWorkplaceId(wantingId, wid);
                setIsWaiting(wantingId, false);
                //isWaiting.put(wantingid, false);
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
        //userInfo.get(waiting)[2] = wid;
        setCurrentWorkplaceId(waiting, wid);
        setIsWaiting(waiting, false);
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
                    && getAction(longestWanting) == Action.ENTER);
    }

    public static void main(String[] args) {
        
    }
}
