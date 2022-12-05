package cp2022.solution;

import cp2022.base.Workplace;

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
        this.workplace.use();
    }
}
