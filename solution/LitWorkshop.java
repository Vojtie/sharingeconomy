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
     *  safety - users can enter a workplace provided that nobody is currently occupying it and
     *      do not violate the liveness (2*N) condition.
     *      They may begin occupying it by themselves if its occupation is set to FREE
     *      or after being woken up by user who just left that workplace (leave()) or already
     *      switched to other (use(other)). Users can switch to other workplace provided that nobody
     *      is occupying it or the one whois currently occupying it is not working and belongs to the
     *      same cycle of users wanting to switch but can start working only after the other user who
     *      wants to switch from that workplace did it.
     *
     *  liveness - users who want to switch or enter are stored in a queue (WantingQueue instance) which holds
     *      information how many users entered the workshop after they started wanting to switch or enter for each
     *      wanting user. If user1 wants to enter the workshop but there is a user who has already passed 2*N-1
     *      entering users, user1 is blocked until the blocking user manages to switch or enter.
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
            users.put(myId, new Semaphore(0));
            setIsWaiting(myId, false);
            setAction(myId, Action.ENTER);
            setWantedWorkplaceId(myId, wid);
            joinQueue();
            var canEnter = canEnter();
            if (!canEnter || getOccupation(wid) != Occupation.FREE) {
                setIsWaiting(myId, true);
                if (!canEnter) {
                    markAsBlocked(myId);
                }
                incrementEnteringCount(wid);
                isWaiting.put(myId, true);
                mutex.release();
                users.get(myId).acquire();
            } else {
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

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutex.acquire();
            var myId = getMyId();
            joinQueue();
            setAction(myId, Action.SWITCH);
            setWantedWorkplaceId(myId, wid);
            setPreviousWorkplaceId(myId, getCurrentWorkplaceId(myId));
            if (getCurrentWorkplaceId(myId).compareTo(wid) == 0) {
                // attempts to switch to the same workplace
                setCurrentWorkplaceId(myId, wid);
                mutex.release();
                
                return workplaces.get(wid);
            }
            setOccupation(getPreviousWorkplaceId(myId), Occupation.SWITCHING);
            addSwitching(getPreviousWorkplaceId(myId), myId);
            if (getOccupation(wid) == Occupation.FREE) {
                removeSwitching(getPreviousWorkplaceId(myId));
                setCurrentWorkplaceId(myId, wid);
                setOccupation(wid, Occupation.WORKING);
                mutex.release();
                
                return workplaces.get(wid);
            } else if (getOccupation(wid) == Occupation.SWITCHING && isCycle(wid)) {
                setOccupation(wid, Occupation.TWO_OCCUPYING);
                wakeUpCycle(getPreviousWorkplaceId(myId));
                removeSwitching(getPreviousWorkplaceId(myId));
                useWaiting.put(wid, myId);
                mutex.release();
            } else {
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
            var myId = getMyId();
            setCurrentWorkplaceId(myId, wid);
            exitQueue();
            if (getAction(myId) == Action.SWITCH && getPreviousWorkplaceId(myId).compareTo(wid) != 0) {
                var prevWid = getPreviousWorkplaceId(myId);
                if (getOccupation(prevWid) == Occupation.TWO_OCCUPYING) {
                    wakeUpUseWaiterAndSet(prevWid);
                } else {
                    var letIn = tryLetIn(prevWid);
                    if (!letIn) {
                        setOccupation(prevWid, Occupation.FREE);
                    }
                }
            }
            var blocking = wanting.getWhoBlocked();
            if (blocking != null && blocking == myId) {
                tryWakeUpBlocked();
            }
            if (getOccupation(wid) == Occupation.TWO_OCCUPYING) {
                // wait for the second guy to leave the workstation
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
    }
    
    long getMyId() {
        return Thread.currentThread().getId();
    }

    void joinQueue() {
        wanting.add(getMyId());
    }

    void exitQueue() {
        wanting.remove(getMyId());
    }

    void addSwitching(WorkplaceId from, Long userId) {
        switching.put(from, userId);
    }

    void removeSwitching(WorkplaceId wid) {
        switching.remove(wid);
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
        while (next != wid) {
            var switching = getSwitching(next);
            removeSwitching(next);
            next = getWantedWorkplaceId(switching);
            setOccupation(next, Occupation.TWO_OCCUPYING);
            setCurrentWorkplaceId(switching, next);
            setIsWaiting(switching, false);
            decrementSwitchingCount(next);
            useWaiting.put(next, switching);
            users.get(switching).release();
        }
    }
    
    long getFirstWantingToEnter(WorkplaceId wid) {
        for (var user : wanting.usersToList()) {
            if (isWaiting(user) && getAction(user) == Action.ENTER
                    && getWantedWorkplaceId(user).compareTo(wid) == 0) {
                return user;
            }
        }
        throw new RuntimeException("no wanting to enter wid despite count > 0");
    }

    void tryWakeUpBlocked() {
        var blocked = wanting.getBlocked();
        var freed = new ArrayList<Long>();
        for (var user : wanting.usersToList()) {
            if (!blocked.contains(user))
                continue;
            var wantedWid = getWantedWorkplaceId(user);
            if (!canEnter() && getFirstWanting() != user)
                break; // remains blocked, the rest as well
            else {
                // waiting for workplace or entering, not blocked anymore
                freed.add(user);
                if (getOccupation(wantedWid) == Occupation.FREE) {
                    setOccupation(wantedWid, Occupation.WORKING);
                    wanting.pass(user);
                    setCurrentWorkplaceId(user, wantedWid);
                    setIsWaiting(user, false);
                    decrementEnteringCount(wantedWid);
                    users.get(user).release(); // let in
                }
            }
        }
        blocked.removeAll(freed); // remove from blocked those who are not blocked anymore
        wanting.updateWhoBlocked();
    }
    
    void setOccupation(WorkplaceId wid, Occupation occ) {
        occupation.put(wid, occ);
    }
    
    Occupation getOccupation(WorkplaceId wid) {
        return occupation.get(wid);
    }
    
    boolean isWaiting(long user) {
        return isWaiting.get(user);
    }

    void setIsWaiting(long user, boolean bool) {
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
        assert res != null;
        return res;
    }
    
    void setAction(long user, Action action) {
        actions.put(user, action);
    }
    
    WorkplaceId getWantedWorkplaceId(long user) {
        var res = wantedWorkplaceIds.get(user);
        assert res != null;
        return res;
    }

    WorkplaceId getCurrentWorkplaceId(long user) {
        var res = currentWorkplaceIds.get(user);
        assert res != null;
        return res;
    }
    
    WorkplaceId getPreviousWorkplaceId(long user) {
        var res = previousWorkplaceIds.get(user);
        assert res != null;
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

        for (var wantingId : wantingUsers) {
            if (isWaiting(wantingId)
                    && getWantedWorkplaceId(wantingId).compareTo(wid) == 0
                    && getAction(wantingId) == action)
            {
                setOccupation(wid, Occupation.WORKING);
                if (action == Action.SWITCH) {
                    removeSwitching(getPreviousWorkplaceId(wantingId));
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
        assert false;
    }

     void wakeUpUseWaiterAndSet(WorkplaceId wid) {
        var waiting = useWaiting.get(wid);
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
    boolean canEnter() {
        return wanting.empty() || wanting.getLongestPassCount() < (2L * n) - 1;
    }

    boolean tryLetIn(WorkplaceId wid) {
        if (getSwitchingCount(wid) > 0) {
            wakeUpAndSet(wid, Action.SWITCH);
            return true;
        }
        if (getEnteringCount(wid) > 0) {
            if (canEnter() || firstWantsToEnter(wid)) {
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
        if (wanting.getBlocked().contains(user)) return;
        wanting.addBlocked(user);
    }
}
