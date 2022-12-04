/*
 * University of Warsaw
 * Concurrent Programming Course 2022/2023
 * Java Assignment
 *
 * Author: Wojciech Kuzebski (wk429552@students.mimuw.edu.pl)
 */


package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.*;

public class LitWorkshop implements Workshop {

    /*
     * Solution overview:
     *  I used decorator pattern on Workplace class (WorkplaceGuard) in order to ensure security of workplaces usage.
     *
     *  safety - users can enter a workplace provided that nobody is currently occupying it -
     *      they can begin occupying it by themselves if its occupation is set to FREE
     *      and do not violate the liveness condition or after being woken up by user
     *      who just left that workplace (leave()) or already switched to other (use(other)).
     *      Users can switch to other workplace provided that nobody is occupying it or the one who
     *      is currently occupying it is not working and belong to the same cycle of users wanting to switch.
     *
     *  liveness - users who want to switch or enter are stored in a queue (WantingQueue instance) which holds
     *      information how many users entered the workshop after they started wanting to switch or enter for each
     *      wanting user. If user1 wants to enter the workshop but there is a user who has already passed 2*N-1
     *      entering users, user1 is blocked until the blocking user manages to switch or enter.
     *
     *
     */
    private final int n;
    private final Map<WorkplaceId, Workplace> workplaces = new ConcurrentHashMap<>();
    private final Map<Long, Semaphore> users = new ConcurrentHashMap<>(); // every thread has its own semaphore
    
    private final Semaphore mutex = new Semaphore(1, true);
    // fields below are protected my mutex
    private final Map<WorkplaceId, Long> switching = new HashMap<>(); // thread wants to switch from wid
    private final Map<WorkplaceId, int[]> counters = new HashMap<>(); // int[] = {entering, switching}
    private final Map<WorkplaceId, Occupation> occupation = new HashMap<>();
    private final Map<WorkplaceId, Long> useWaiting = new HashMap<>(); // thread is waiting in use() - used to manage cycles of switchTo()
    
    private final Map<Long, Action> actions = new HashMap<>();
    private final Map<Long, WorkplaceId> wantedWorkplaceIds = new HashMap<>();
    private final Map<Long, WorkplaceId> previousWorkplaceIds = new HashMap<>();
    private final Map<Long, WorkplaceId> currentWorkplaceIds = new HashMap<>();
    private final Map<Long, Boolean> isWaiting = new HashMap<>();
    
    private final WantingQueue wanting;

    LitWorkshop(Collection<Workplace> workplaces) {
        n = workplaces.size();
        for (var workplace : workplaces) {
            var wid = workplace.getId();
            this.workplaces.put(
                    wid, 
                    new WorkplaceGuard(workplace, this));
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
            //System.out.println(myId + " attempts to enter " + wid);
            users.put(myId, new Semaphore(0));
            setIsWaiting(myId, false);
            setAction(myId, Action.ENTER);
            setWantedWorkplaceId(myId, wid);
            joinQueue();
            var canEnter = canEnter(wid);
            if (!canEnter || getOccupation(wid) != Occupation.FREE) {
                //System.out.println(myId + " cant enter " + wid + " " + getOccupation(wid) + ", waiting");
                setIsWaiting(myId, true);
                if (!canEnter) {
                    assert wanting.getLongestPassCount() == (2L * n) - 1;
                    markAsBlocked(myId);
                }
                incrementEnteringCount(wid);
                isWaiting.put(myId, true);
                assert  users.get(myId).availablePermits() == 0;
                mutex.release();
                users.get(myId).acquire();
            } else {
                //System.out.println(myId + " enters " + wid);
                assert getOccupation(wid) == Occupation.FREE;
                setOccupation(wid, Occupation.WORKING);
                wanting.pass(myId);
                setIsWaiting(myId, false);
                setCurrentWorkplaceId(myId, wid);
                mutex.release();
            }
            
            return workplaces.get(wid);
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    void joinQueue() {
        wanting.add(getMyId());
    }
    
    void exitQueue() {
        wanting.remove(getMyId());
    }

    void addSwitching(WorkplaceId from, Long userId) {
        assert switching.get(from) == null;
        switching.put(from, userId);
    }

    void removeSwitching(WorkplaceId wid) {
        var removed = switching.remove(wid);
        assert removed != null;
    }

    Long getSwitching(WorkplaceId wid) {
        return switching.get(wid);
    }

    WorkplaceId getNextWid(WorkplaceId wid) {
        var switcherId = getSwitching(wid);
        if (switcherId != null) {
            return getWantedWorkplaceId(switcherId);
        }
        return null;
    }

    boolean isCycle(WorkplaceId wid) {
        var rabbit = getNextWid(wid);
        var turtle = wid;
        while (rabbit != null && turtle != null && rabbit != turtle) {
            rabbit = getNextWid(rabbit);
            if (rabbit == null) break;
            rabbit = getNextWid(rabbit);
            turtle = getNextWid(turtle);
        }
        return rabbit != null && turtle != null;
    }

    void wakeUpCycle(WorkplaceId wid) {
        var next = getNextWid(wid);
        assert next != null;
        //System.out.println(getMyId() + " begin waking up cycle:");
        while (next != wid) {
            var switching = getSwitching(next);
            removeSwitching(next);
            assert switching != null;
            next = getWantedWorkplaceId(switching);
            assert next != null;
            //System.out.println("Waking up " + switching + " from " + prev + " to " + next);
            setOccupation(next, Occupation.TWO_OCCUPYING);
            setCurrentWorkplaceId(switching, next);
            assert isWaiting(switching);
            setIsWaiting(switching, false);
            decrementSwitchingCount(next);
            useWaiting.put(next, switching);
            assert users.get(switching).availablePermits() == 0;
            users.get(switching).release();
        }
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutex.acquire();
            //System.out.println(getMyId() + " attempts to switch to " + wid + " from " + getCurrentWorkplaceId(getMyId()));
            var myId = getMyId();
            joinQueue();
            assert !isWaiting(myId);
            setAction(myId, Action.SWITCH);
            setWantedWorkplaceId(myId, wid);
            setPreviousWorkplaceId(myId, getCurrentWorkplaceId(myId));
            if (getCurrentWorkplaceId(myId).compareTo(wid) == 0) {
                // attempts to switch to the same workplace
                //System.out.println(myId + " attempts to switch to the same workplace");
                assert getOccupation(wid) == Occupation.WORKING;
                setCurrentWorkplaceId(myId, wid);
                mutex.release();
                
                return workplaces.get(wid);
            }
            setOccupation(getPreviousWorkplaceId(myId), Occupation.SWITCHING);
            addSwitching(getPreviousWorkplaceId(myId), myId);
            if (getOccupation(wid) == Occupation.FREE) {
                //System.out.println(getMyId() + " can switch to " + wid + " : " + getOccupation(wid) + ", occupying");
                removeSwitching(getPreviousWorkplaceId(myId));
                setCurrentWorkplaceId(myId, wid);
                setOccupation(wid, Occupation.WORKING);
                mutex.release();
                
                return workplaces.get(wid);
            } else if (getOccupation(wid) == Occupation.SWITCHING && isCycle(wid)) {
                //System.out.println(getMyId() + "found cycle, can switch to " + wid + " : " + getOccupation(wid));
                setOccupation(wid, Occupation.TWO_OCCUPYING);
                wakeUpCycle(getPreviousWorkplaceId(myId));
                removeSwitching(getPreviousWorkplaceId(myId));
                useWaiting.put(wid, myId);
                mutex.release();
            } else {
                //System.out.println(getMyId() + " cant switch to " + wid + " : " + getOccupation(wid));
                assert users.get(myId).availablePermits() == 0;
                incrementSwitchingCount(wid);
                setIsWaiting(myId, true);
                mutex.release();
                users.get(myId).acquire(); // woke up by leave() or use() on other wid (!= myPrevWid)
            }
            
            return workplaces.get(wid);
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    void use(WorkplaceId wid) {
        try {
            this.mutex.acquire();
            //System.out.println(getMyId() + " attempts to use " + wid + " : " + getOccupation(wid));
            var myId = getMyId();
            setCurrentWorkplaceId(myId, wid);
            exitQueue();
            if (getAction(myId) == Action.SWITCH && getPreviousWorkplaceId(myId).compareTo(wid) != 0) {
                var prevWid = getPreviousWorkplaceId(myId);
                if (getOccupation(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert getOccupation(prevWid) == Occupation.SWITCHING;
                    var letIn = tryLetIn(prevWid);
                    if (!letIn) {
                        setOccupation(prevWid, Occupation.FREE);
                    }
                }
            } else assert actions.get(myId) != Action.SWITCH || (getPreviousWorkplaceId(myId).compareTo(wid) == 0 && getOccupation(wid) == Occupation.WORKING);

            var blocking = wanting.getWhoBlocked();
            if (blocking != null && blocking == myId) {
                tryWakeUpBlocked();
            }
            if (getOccupation(wid) == Occupation.TWO_OCCUPYING) {
                // wait for the second guy to leave the workstation
                assert getAction(myId) == Action.SWITCH;
                setIsWaiting(myId, true);
                // one who wakes sets all including removing useWaiting
                mutex.release();
                users.get(myId).acquire();
            } else {
                setOccupation(wid, Occupation.WORKING);
                mutex.release();
            }
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
            //System.out.println(myId + " leaves, prevWid switching: " + getSwitchingCount(prevWid) + " entering: " + getEnteringCount(prevWid));
            assert useWaiting.get(prevWid) == null;
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
        var myId = getMyId();
        users.remove(myId);
        wantedWorkplaceIds.remove(myId);
        previousWorkplaceIds.remove(myId);
        currentWorkplaceIds.remove(myId);
        isWaiting.remove(myId);
        assert !wanting.remove(myId); // have been done in use()
    }
    
    long getMyId() {
        return Thread.currentThread().getId();
    }
    
    long getFirstWantingToEnter(WorkplaceId wid) {
        assert getEnteringCount(wid) > 0;
        for (var user : wanting.usersToList()) {
            if (isWaiting(user) && getAction(user) == Action.ENTER
                    && getWantedWorkplaceId(user).compareTo(wid) == 0) {
                return user;
            }
        }
        throw new RuntimeException("no wanting to enter wid despite count > 0");
    }

    void tryWakeUpBlocked() {
        //System.out.println(getMyId() + " attempting to wake up blocked");
        var blocked = wanting.getBlocked();
        var freed = new ArrayList<Long>();
        for (var user : wanting.usersToList()) {
            if (!blocked.contains(user))
                continue;
            var blockedUser = user;
            assert getAction(blockedUser) == Action.ENTER;
            assert isWaiting(blockedUser);
            var wantedWid = getWantedWorkplaceId(blockedUser);
            if (!canEnter(wantedWid) && getFirstWanting() != blockedUser)
                break; // remains blocked, the rest as well
                //assert wanting.getLongestWaiting() != blockedUser;
                //assert wanting.getLongestPassCount() == (2L * n) - 1;
                //System.out.println(getMyId() + " blocked " + blockedUser + " still blocked by " + wanting.getLongestWaiting() + " with pass count == 2N-1? " + (wanting.getLongestPassCount() == 2L*n-1) + " isWaiting " + isWaiting(getFirstWanting()));
            else {
                // waiting for workplace or entering, not blocked anymore
                freed.add(blockedUser);
                if (getOccupation(wantedWid) == Occupation.FREE) {
                    //System.out.println(getMyId() + " blocked " + blockedUser + " freed and entering");
                    setOccupation(wantedWid, Occupation.WORKING);
                    wanting.pass(blockedUser);
                    setCurrentWorkplaceId(blockedUser, wantedWid);
                    setIsWaiting(blockedUser, false);
                    decrementEnteringCount(wantedWid);
                    users.get(blockedUser).release(); // let in
                } else {
                    //System.out.println(getMyId() + " blocked " + blockedUser + " freed not entering");
                }
            }
        }
        blocked.removeAll(freed); // remove from blocked those who are not blocked anymore
        wanting.updateWhoBlocked();
    }
    
    void setOccupation(WorkplaceId wid, Occupation occ) {
        //System.out.println(getMyId() + " set occ on " + wid + " to " + occ);
        occupation.put(wid, occ);
    }
    
    Occupation getOccupation(WorkplaceId wid) {
        return occupation.get(wid);
    }
    
    boolean isWaiting(long user) {
        return isWaiting.get(user);
    }

    void setIsWaiting(long user, boolean bool) {
        //System.out.println(getMyId() + " set isWaiting to " + bool + " for user " + user);
        isWaiting.put(user, bool);
    }

    int getEnteringCount(WorkplaceId wid) {
        return counters.get(wid)[0];
    }
    
    int getSwitchingCount(WorkplaceId wid) {
        return counters.get(wid)[1];
    }
    
    void incrementEnteringCount(WorkplaceId wid) {
        counters.get(wid)[0]++;
    }
    
    void decrementEnteringCount(WorkplaceId wid) {
        counters.get(wid)[0]--;
    }
    
    void incrementSwitchingCount(WorkplaceId wid) {
        counters.get(wid)[1]++;
    }
    
    void decrementSwitchingCount(WorkplaceId wid) {
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
        var res = wantedWorkplaceIds.get(user);
        if (res == null) {
            throw new RuntimeException("null wantedWid");
        }
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
        wantedWorkplaceIds.put(user, wid);
    }

    void setCurrentWorkplaceId(long user, WorkplaceId wid) {
        currentWorkplaceIds.put(user, wid);
    }

    void setPreviousWorkplaceId(long user, WorkplaceId wid) {
        previousWorkplaceIds.put(user, wid);
    }

     void wakeUpAndSet(WorkplaceId wid, Action action) {
        // wake up first such that isWaiting to occupy wid

        var wantingUsers = wanting.usersToList();
        //System.out.println(getMyId() + " waking up " + action + " on " + wid);

        for (var wantingId : wantingUsers) {
//            ////System.out.println("wanting user: " + wantingId + " | action:" + getAction(wantingId)
//                    + " | isWaiting: " + isWaiting(wantingId) + " | wanted workplaceId: " + getWantedWorkplaceId(wantingId));
            if (isWaiting(wantingId) && getWantedWorkplaceId(wantingId).compareTo(wid) == 0
                    && getAction(wantingId) == action) {
                //System.out.println(getMyId() + " let in " + wantingId + " " + getAction(wantingId) + " on " + wid);
                setOccupation(wid, Occupation.WORKING);
                if (action == Action.SWITCH) {
                    removeSwitching(getPreviousWorkplaceId(wantingId));
                    //setPreviousWorkplaceId(wantingId, getCurrentWorkplaceId(wantingId));
                    decrementSwitchingCount(wid);
                } else {
                    wanting.pass(wantingId);
                    decrementEnteringCount(wid);
                    wanting.getBlocked().remove(wantingId);
                }
                setCurrentWorkplaceId(wantingId, wid);
                setIsWaiting(wantingId, false);
                users.get(wantingId).release();
                return;
            }
        }
        throw new RuntimeException("unexpected error: no one to be woken up");
    }

     void wakeUpUseWaiterAndSet(WorkplaceId wid) {
        assert useWaiting.get(wid) != null;
        var waiting = useWaiting.get(wid);
        //System.out.println(getMyId() + " waking up useWaiter " + waiting + " on " + wid);
        setOccupation(wid, Occupation.WORKING);
        setCurrentWorkplaceId(waiting, wid);
        useWaiting.remove(wid);
        if (isWaiting(waiting)) {
            setIsWaiting(waiting, false);
            users.get(waiting).release();
        }
    }

    // checks whether new user can enter the workshop
    // (doesn't violate the 2*N condition)
     boolean canEnter(WorkplaceId wid) {
        return wanting.empty() || wanting.getLongestPassCount() < (2L * n) - 1;
    }

    boolean tryLetIn(WorkplaceId wid) {
        if (getSwitchingCount(wid) > 0) {
            wakeUpAndSet(wid, Action.SWITCH);
            return true;
        }
        if (getEnteringCount(wid) > 0) {
            if (canEnter(wid) || firstWantsToEnter(wid)) {
                wakeUpAndSet(wid, Action.ENTER);
                return true;
            } else markAsBlocked(getFirstWantingToEnter(wid));
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
        if (wanting.getBlocked().contains(user))
            return;
        //System.out.println(getMyId() + " marking " + user + " as blocked");
        //wanting.printBlocked();
        assert getAction(user) == Action.ENTER;
        assert wanting.getLongestPassCount() == 2L*n-1;
        assert getOccupation(getWantedWorkplaceId(wanting.getLongestWaiting())) != Occupation.FREE;
        wanting.addBlocked(user);
    }
}
