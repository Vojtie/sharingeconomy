class WorkplaceGuard extends Workplace {

    private final Workplace workplace;

    WorkplaceGuard(Workplace workplace) {
        super(workplace.getId());
        this.workplace = workplace;
    }

    @Override
    public void use() {
        // guard
        workplace.use();
    }
}
