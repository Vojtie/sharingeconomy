import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LitWorkshop implements Workshop {

    private static int N = 0;
    private final ConcurrentHashMap<Long, WorkplaceId> tidToWid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Semaphore> widToEnterMutex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, Semaphore> widToWorkplaceMutex = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<WorkplaceId, Semaphore> countsMutex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, AtomicInteger> widToEnterWaitCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, AtomicInteger> widToWorkplaceWaitCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WorkplaceId, boolean[]> isOccupied = new ConcurrentHashMap<>();

    private final Semaphore passQueueMutex = new Semaphore(1, true); // chroni przed wpuszczaniem nowych watkow do warsztatu
    private final ArrayList<Long> tidWantQueue = new ArrayList<>();
    private final Map<Long, AtomicInteger> tidToPassCnt = new HashMap<>();

    private final ConcurrentHashMap<WorkplaceId, Workplace> workplaces = new ConcurrentHashMap<>();
    //private final Semaphore
    LitWorkshop(Collection<Workplace> workplaces) {
        N = workplaces.size();
        for (var workplace : workplaces) {
            this.workplaces.put(workplace.getId(), workplace);
            countsMutex.put(
                    workplace.getId(),
                    new Semaphore(1, true)
            );
            widToEnterMutex.put(
                    workplace.getId(),
                    new Semaphore(1, true)
            );
            widToWorkplaceMutex.put(
                    workplace.getId(),
                    new Semaphore(1, true)
            );
            widToEnterWaitCount.put(
                    workplace.getId(),
                    new AtomicInteger(0)
            );
            widToWorkplaceWaitCount.put(
                    workplace.getId(),
                    new AtomicInteger(0)
            );
            isOccupied.put(
                    workplace.getId(),
                    new boolean[]{false}
            );
        }
    }
    private int getLongestPassCount() {
        if (tidWantQueue.size() > 0) {
            return tidToPassCnt.get(tidWantQueue.get(0)).get();
        }
        return -1;
    }
    @Override
    public Workplace enter(WorkplaceId wid) {
        try {
            countsMutex.get(wid).acquire();
            tidToWid.put(Thread.currentThread().getId(), wid);
            passQueueMutex.acquire(); // nikt nie zostanie wpuszczony
            tidWantQueue.add(Thread.currentThread().getId());
            tidToPassCnt.put(Thread.currentThread().getId(), new AtomicInteger(0));
            if (getLongestPassCount() == 2 * N - 1 || isOccupied.get(wid)[0] || widToEnterWaitCount.get(wid).get() > 0 || widToWorkplaceWaitCount.get(wid).get() > 0) {
                    widToEnterWaitCount.get(wid).incrementAndGet();
                    passQueueMutex.release();
                    countsMutex.get(wid).release();
                    widToEnterMutex.get(wid).acquire();
            }
            if (isOccupied.get(wid)[0]) {

            }
        } catch (InterruptedException e) {

        }
        return null;
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        return null;
    }

    @Override
    public void leave() {
        // jesli ktos czeka na zmiane stanowiska 2N-1
        // wejsc to nie wpuszczaj nowego watku do warsztatu
        try {
            countsMutex.get(getCurrentWorkplaceId()).acquire();
            if (getWaitersToSwitchCount(getCurrentWorkplaceId()) > 0) {
                // ktos czeka na zmiane na to stanowisko - wpuszczamy i przekazujemy mutexy (counts i workplace)
                widToWorkplaceMutex.get(getCurrentWorkplaceId()).release();
            } else if (getWaitersToEnterCount(getCurrentWorkplaceId()) > 0) {
                // jest nowy watek ktory chce wejsc do warsztatu na to miejsce
                // musimy sprawdzic czy ktos nie przepusci 2N-ty raz
                passQueueMutex.acquire(); // nikogo nikt nie wpusci zanim nie skonczymy
                if (tidWantQueue.size() > 0) {
                    var longestPassCnt = tidToPassCnt.get(tidWantQueue.get(0));
                    if (longestPassCnt.get() < 2 * N - 1) {
                        // mozemy wpuscic nowy watek do warsztatu
                        // przekazujemy mu mutexa do workplace i counts
                        widToEnterMutex.get(getCurrentWorkplaceId()).release();
                    }
//                    else if (longestPassCnt == 2 * N - 1) {
//                        // nie mozemy wpuscic nowego watku do warsztatu
//                        // zanim nie obejmie stanowiska longestPasser
//                        // chyba pomijamy to - tak jakby nikt nie czekal - budzi ten co nie moze wiecej przepuscic
//                    } else {
//                        throw new RuntimeException("Too many passers");
//                    }
                }
                passQueueMutex.release();
            }
            // nikt nie czeka wiec musimy zostawic wolne stanowisko
            // podnosimy ten semafor dla switcherow wiedzac ze nikt tam nie czeka -
            // entersi tez beda musieli go przejsc
            else {
                isOccupied.get(getCurrentWorkplaceId())[0] = false;
                // kolejnosc?
                countsMutex.get(getCurrentWorkplaceId()).release();
                widToWorkplaceMutex.get(getCurrentWorkplaceId()).release();
            }
        } catch (InterruptedException e) {

        }
    }

    private WorkplaceId getCurrentWorkplaceId() {
        return tidToWid.get(Thread.currentThread().getId());
    }
    private int getWaitersToSwitchCount(WorkplaceId wid) {
        return widToWorkplaceWaitCount.get(wid).get();
    }

    private int getWaitersToEnterCount(WorkplaceId wid) {
        return widToEnterWaitCount.get(wid).get();
    }
    
    public static void main(String[] args) {
        
    }
}
