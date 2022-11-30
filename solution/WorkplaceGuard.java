class WorkplaceGuard extends Workplace {

    private final Workplace workplace;
    private final LitWorkshop workshop;

    WorkplaceGuard(Workplace workplace, LitWorkshop workshop) {
        super(workplace.getId());
        this.workplace = workplace;
        this.workshop = workshop;
    }

    @Override
    public void use() {
        workshop.use(getId());
        /**
        try {
            // state is set
            workshop.mutex.acquire();
            workshop.wanting.remove(workshop.getMyId()); // exit Queue
            if (workshop.getWantedAction(workshop.getMyId()).equals("switch")) {
                var prevWid = workshop.getWantedWorkplaceId(workshop.getMyId()); // previous
                if (workshop.occupation.get(prevWid) == Occupation.TWO_OCCUPYING) {
                    workshop.wakeUpUseWaiterAndSet(prevWid);
                } else {
                    assert workshop.occupation.get(prevWid) == Occupation.ONE_OCCUPYING;
                    if (workshop.getSwitchersCount(prevWid) > 0) {
                        workshop.wakeUpAndSet(prevWid, "switch");
                    } else if (workshop.getEnterersCount(prevWid) > 0 && workshop.canEnter(prevWid)) {
                        workshop.wakeUpAndSet(prevWid, "enter");
                    } else { // can't let in anybody, leave prev as free
                        workshop.occupation.put(prevWid, Occupation.FREE);
                        if (workshop.getEnterersCount(prevWid) > 0) {
                            assert(!workshop.canEnter(prevWid)); // can't enter
                            workshop.wanting.addBlocked(workshop.findFirstWantingToEnter(prevWid));
                        }
                    }
                }
            }
            var wid = getId();
            // if i blocked s1
            if (workshop.wanting.getWhoBlocked() == workshop.getMyId()) {
                workshop.wakeUpBlocked();
            }
            // wait for the second guy to leave the workstation
            if (workshop.occupation.get(wid) == Occupation.TWO_OCCUPYING) {
                assert workshop.useWaiters.get(wid) == null;
                assert workshop.getWantedAction(workshop.getMyId()).equals("switch");
                workshop.useWaiters.put(wid, workshop.getMyId());
                // one who wakes sets all including removing useWaiters
                workshop.users.get(workshop.getMyId()).acquire();
            } else {
                workshop.occupation.put(wid, Occupation.WORKING);
                workshop.mutex.release();
            }
            this.workplace.use();
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
         **/
        this.workplace.use();
    }
}
