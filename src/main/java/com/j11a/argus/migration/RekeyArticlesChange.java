package com.j11a.argus.migration;

import java.sql.Connection;
import java.sql.SQLException;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;

public class RekeyArticlesChange implements CustomTaskChange {

    private @Nullable RekeyReport report;

    public RekeyArticlesChange() {
        // Liquibase instantiates custom changes reflectively and needs a public no-arg constructor
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        try {
            Connection c = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
            this.report = new ArticleRekeyer().rekey(c);
        } catch (SQLException e) {
            throw new CustomChangeException(e);
        }
    }

    @Override
    public String getConfirmationMessage() {
        if (report == null) {
            return "Re-keyed articles: seen=0, rekeyed=0, collapsed=0, linksFolded=0";
        }
        return "Re-keyed articles: seen=" + report.seen()
                + ", rekeyed=" + report.rekeyed()
                + ", collapsed=" + report.collapsed()
                + ", linksFolded=" + report.linksFolded();
    }

    @Override
    public void setUp() throws SetupException {
        // no setup needed
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // no resource access needed
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
