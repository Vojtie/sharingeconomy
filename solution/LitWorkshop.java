package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

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
            setOccupation(
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
            System.out.println(myId + " attempts to enter " + wid);
            users.put(myId, new Semaphore(0));
            setIsWaiting(myId, false);
            setAction(myId, Action.ENTER);
            setWantedWorkplaceId(myId, wid);
            //userInfo.put(myId, new Object[]{"enter", wid, null, false}); // action, wantedWid, curWid, isWaiting
            wanting.add(myId);
            var canEnter = canEnter(wid);
            if (!canEnter || occupation.get(wid) != Occupation.FREE) {
                System.out.println(myId + " cant enter " + wid + " " + occupation.get(wid) + ", waiting");
                setIsWaiting(myId, true);
                if (!canEnter) {
                    assert wanting.getLongestPassCount() == (2L * n) - 1;
                    markAsBlocked(myId);
                }
                incrementEnterersCount(wid);
                isWaiting.put(myId, true);
                assert  users.get(myId).availablePermits() == 0;
                mutex.release();
                users.get(myId).acquire(); // brak dsk
            } else {
                System.out.println(myId + " enters " + wid);
                assert occupation.get(wid) == Occupation.FREE;
                setOccupation(wid, Occupation.WORKING);
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
            System.out.println(getMyId() + " attempts to use " + wid + " : " + occupation.get(wid));
            var myId = getMyId();
            wanting.remove(myId); // exit Queue
            if (getAction(myId) == Action.SWITCH && getPreviousWorkplaceId(myId).compareTo(wid) != 0) {
                var prevWid = getPreviousWorkplaceId(myId);
                if (occupation.get(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert occupation.get(prevWid) == Occupation.ONE_OCCUPYING;
//                    if (getSwitchersCount(prevWid) > 0) {
//                        wakeUpAndSet(prevWid, Action.SWITCH);
//                    } else if (getEnterersCount(prevWid) > 0 && canEnter(prevWid)) {
//                        wakeUpAndSet(prevWid, Action.ENTER);
                    var letIn = tryLetIn(prevWid);
                    if (!letIn) {
                    //} else { // can't let in anybody, leave prev as free
                        setOccupation(prevWid, Occupation.FREE);
                    }
                }
            } else
                assert actions.get(myId) != Action.SWITCH ||
                        (getPreviousWorkplaceId(myId).compareTo(wid) == 0 && occupation.get(wid) == Occupation.WORKING);
            // if i blocked s1
            if (wanting.getWhoBlocked() != null && wanting.getWhoBlocked() == myId) {
                wakeUpBlocked();
            }
            // wait for the second guy to leave the workstation
            if (occupation.get(wid) == Occupation.TWO_OCCUPYING) {
//                if (useWaiters.get(wid) != null) {
//                    //system.out.println("useWaiter: " + useWaiters.get(wid));
//                    assert false;
//                }
                assert getAction(myId) == Action.SWITCH;
                //useWaiters.put(wid, myId);
                setIsWaiting(myId, true);
                // one who wakes sets all including removing useWaiters
                mutex.release();
                users.get(myId).acquire();
            } else {
                setOccupation(wid, Occupation.WORKING);
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
            System.out.println(getMyId() + " attempts to switch to " + wid + " from " + getCurrentWorkplaceId(getMyId()));
            var myId = getMyId();
            wanting.add(myId);
            assert !isWaiting(myId); //isWaiting.put(myId, false);
            setAction(myId, Action.SWITCH);
            setWantedWorkplaceId(myId, wid);
            setPreviousWorkplaceId(myId, getCurrentWorkplaceId(myId));
            if (getCurrentWorkplaceId(myId).compareTo(wid) == 0) {
                // attempts to switch to the same workplace
                System.out.println(myId + " attempts to switch to the same workplace");
                assert occupation.get(wid) == Occupation.WORKING;
                setCurrentWorkplaceId(myId, wid);
                mutex.release();
                return workplaces.get(wid);
            }
            setOccupation(getPreviousWorkplaceId(myId), Occupation.ONE_OCCUPYING); // set previous state
            if (occupation.get(wid) == Occupation.WORKING || occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                System.out.println(getMyId() + " cant switch to " + wid + " : " + occupation.get(wid) + ", waiting");
                System.out.println(users.get(myId).availablePermits());
                incrementSwitchersCount(wid);
                setIsWaiting(myId, true);
                mutex.release();
                users.get(myId).acquire(); // woke up by leave() or use() on other wid
            } else {
                System.out.println(getMyId() + " can switch to " + wid + " : " + occupation.get(wid) + ", occupying");
                //setWantedWorkplaceId(getCurrentWorkplaceId()); // remember previous wid on wanted
                //system.out.println("thread " + myId + " switched");
                setCurrentWorkplaceId(myId, wid);
                if (occupation.get(wid) == Occupation.ONE_OCCUPYING) {
                    setOccupation(wid, Occupation.TWO_OCCUPYING);
                    useWaiters.put(wid, myId);
                } else {
                    assert occupation.get(wid) == Occupation.FREE;
                    setOccupation(wid, Occupation.WORKING);
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
            System.out.println(myId + " leaves, prevWid switchers: " + getSwitchersCount(prevWid)
                + " enterers: " + getEnterersCount(prevWid));
            assert useWaiters.get(prevWid) == null;
//            if (useWaiters.get(prevWid) != null) {
//                throw new RuntimeException("unexpected: should only be possible in switch not leave");
//            }
//            else if (getSwitchersCount(prevWid) > 0) {
//                wakeUpAndSet(prevWid, Action.SWITCH);
//            } else if (canEnter(prevWid) && getEnterersCount(prevWid) > 0) {
//                wakeUpAndSet(prevWid, Action.ENTER);
//            } else {
//                if (getEnterersCount(prevWid) > 0) {
//                    // cant enter
//                    markAsBlocked(findFirstWantingToEnter(prevWid));
//                }
//                setOccupation(prevWid, Occupation.FREE);
//            }
            if (!tryLetIn(prevWid)) {
                setOccupation(prevWid, Occupation.FREE);
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
        return Thread.currentThread().getId() - 13;
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

    void wakeUpBlocked() { // if they can enter
        System.out.println(getMyId() + " attempting to wake up blocked");
        var blocked = wanting.getBlocked();
        var freed = new ArrayList<Long>();
        for (var user : wanting.usersToList()) {
            if (!blocked.contains(user))
                continue;
            var blockedUser = user;
            assert getAction(blockedUser) == Action.ENTER;
            assert isWaiting(blockedUser);
            var wantedWid = getWantedWorkplaceId(blockedUser);
            if (!canEnter(wantedWid) && getFirstWanting() != blockedUser) { // still blocked
                assert wanting.getLongestWaiting() != blockedUser;
                assert wanting.getLongestPassCount() == (2L * n) - 1;
                System.out.println(getMyId() + " blocked " + blockedUser + " still blocked by " + wanting.getLongestWaiting()
                    + " with pass count == 2N-1? " + (wanting.getLongestPassCount() == 2L*n-1) + " isWaiting " + isWaiting(getFirstWanting()));
                break; // the rest still blocked too
            } else {
                // waiting for workplace or entering, not blocked anymore
                freed.add(blockedUser);
                if (occupation.get(wantedWid) == Occupation.FREE) {
                    System.out.println(getMyId() + " blocked " + blockedUser + " freed and entering");
                    setOccupation(wantedWid, Occupation.WORKING);
                    wanting.pass(blockedUser);
                    setCurrentWorkplaceId(blockedUser, wantedWid);
                    setIsWaiting(blockedUser, false);
                    decrementEnterersCount(wantedWid);
                    users.get(blockedUser).release(); // let in
                } else {
                    System.out.println(getMyId() + " blocked " + blockedUser + " freed not entering");
                }
            }
        }
        blocked.removeAll(freed); // remove from blocked those that are not blocked anymore
        wanting.updateWhoBlocked(); // set who blocked USELESS TODO
    }
    
    void setOccupation(WorkplaceId wid, Occupation occ) {
        System.out.println(getMyId() + " set occ on " + wid + " to " + occ);
        occupation.put(wid, occ);
    }
    
    boolean isWaiting(long user) {
        return isWaiting.get(user);
    }

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
//        if (!isWaiting(user))
//            throw new RuntimeException("checked wantedWid despite not waiting");
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
        System.out.println(getMyId() + " waking up " + action + " on " + wid);

        for (var wantingId : wantingUsers) {
//            //system.out.println("wanting user: " + wantingId + " | action:" + getAction(wantingId)
//                    + " | isWaiting: " + isWaiting(wantingId) + " | wanted workplaceId: " + getWantedWorkplaceId(wantingId));
            if (isWaiting(wantingId) && getWantedWorkplaceId(wantingId).compareTo(wid) == 0
                    && getAction(wantingId) == action) {
                System.out.println(getMyId() + " let in " + wantingId + " " + getAction(wantingId) + " on " + wid);
                setOccupation(wid, Occupation.WORKING);
                if (action == Action.SWITCH) {
                    //userInfo.get(wantingId)[1] = userInfo.get(wantingId)[2];
                    setPreviousWorkplaceId(wantingId, getCurrentWorkplaceId(wantingId));
                    decrementSwitchersCount(wid);
                } else {// if (action == Action.ENTER) {
                    wanting.pass(wantingId);
                    decrementEnterersCount(wid);
                    if (wanting.getBlocked().remove(wantingId)) {
                        System.out.println(getMyId() + " let in blocked " + wantingId + " before other blocked");
                    }
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
        System.out.println(getMyId() + " waking up useWaiter " + waiting + " on " + wid);
        setOccupation(wid, Occupation.WORKING);
        //decrementUsersCount(wid);
        //userInfo.get(waiting)[2] = wid;
        setCurrentWorkplaceId(waiting, wid);
        useWaiters.remove(wid);
        if (isWaiting(waiting)) {
            setIsWaiting(waiting, false);
            users.get(waiting).release();
        }
    }

    // checks whether new user can enter the workshop
    // (doesn't violate the 2*N condition)
     boolean canEnter(WorkplaceId wid) {
        return wanting.empty() || wanting.getLongestPassCount() < (2L * n) - 1;
//        if (wanting.empty())
//            return true;
//        var longestWanting = wanting.getLongestWaiting();
//        return wanting.getLongestPassCount() < (2L * n) - 1
//                || (wanting.getLongestPassCount() == (2L * n) - 1
//                    && isWaiting(longestWanting)
//                    && getWantedWorkplaceId(longestWanting).compareTo(wid) == 0
//                    && getAction(longestWanting) == Action.ENTER
//        );
    }

    boolean tryLetIn(WorkplaceId wid) {
        if (getSwitchersCount(wid) > 0) {
            wakeUpAndSet(wid, Action.SWITCH);
            return true;
        }
        if (getEnterersCount(wid) > 0) {
            if (canEnter(wid) || firstWantsToEnter(wid)
            ) {
                wakeUpAndSet(wid, Action.ENTER);
                return true;
            } else //if (!wanting.getBlocked().contains(user))
                markAsBlocked(findFirstWantingToEnter(wid));
        }
        return false;
    }

    boolean firstWantsToEnter(WorkplaceId wid) {
        var longestWanting = wanting.getLongestWaiting();
        return wanting.getLongestPassCount() == (2L * n) - 1
                && isWaiting(longestWanting)
                && getWantedWorkplaceId(longestWanting).compareTo(wid) == 0
                && getAction(longestWanting) == Action.ENTER;
    }

    long getFirstWanting() {
        return wanting.getLongestWaiting();
    }
    
    void markAsBlocked(long user) {
        //assert isWaiting(user); - enter calls on self before
        if (wanting.getBlocked().contains(user))
            return;
        System.out.println(getMyId() + " marking " + user + " as blocked");
        wanting.printBlocked();
        assert getAction(user) == Action.ENTER;
        assert wanting.getLongestPassCount() == 2L*n-1;
        //assert isWaiting(wanting.getLongestWaiting());
        assert occupation.get(getWantedWorkplaceId(wanting.getLongestWaiting())) != Occupation.FREE;
        //assert !wanting.getBlocked().contains(user);
        wanting.addBlocked(user);
    }

    public static void main(String[] args) {
        
    }
}
